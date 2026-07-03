(function () {
function deepMerge(target, src) {
  if (src == null || typeof src !== "object") return target;
  for (const k of Object.keys(src)) {
    if (k === "__proto__" || k === "constructor" || k === "prototype") continue;
    const v = src[k];
    if (v && typeof v === "object" && !Array.isArray(v) &&
        target[k] && typeof target[k] === "object" && !Array.isArray(target[k])) {
      deepMerge(target[k], v);
    } else {
      target[k] = v;
    }
  }
  return target;
}

function applyPatch(base, path, data) {
  let node = base;
  for (let i = 0; i < path.length; i++) {
    const key = path[i];
    if (key === "__proto__" || key === "constructor" || key === "prototype") return;
    node = node[key];
    if (node == null) return;
  }
  deepMerge(node, data);
}

function foldMultipart(text) {
  const segments = text.split("--graphql");
  const objects = [];
  for (const seg of segments) {
    const start = seg.indexOf("{");
    if (start === -1) continue;
    let depth = 0, end = -1, inStr = false, esc = false;
    for (let j = start; j < seg.length; j++) {
      const c = seg[j];
      if (inStr) {
        if (esc) esc = false;
        else if (c === "\\") esc = true;
        else if (c === '"') inStr = false;
      } else if (c === '"') inStr = true;
      else if (c === "{") depth++;
      else if (c === "}") { depth--; if (depth === 0) { end = j; break; } }
    }
    if (end === -1) continue;
    try { objects.push(JSON.parse(seg.slice(start, end + 1))); } catch (e) { /* skip */ }
  }
  if (objects.length === 0) return null;
  const base = objects[0];
  // @defer incremental `path` is relative to the response data root.
  const root = base.data || base;
  for (let k = 1; k < objects.length; k++) {
    const inc = objects[k].incremental;
    if (!Array.isArray(inc)) continue;
    for (const patch of inc) applyPatch(root, patch.path || [], patch.data);
  }
  delete base.incremental;
  delete base.hasNext;
  return base;
}

function decodeBody(text, contentType) {
  if (contentType && contentType.indexOf("multipart") !== -1) return foldMultipart(text);
  try { return JSON.parse(text); } catch (e) { return null; }
}

// Parses a folded GraphQL response connection into balances.
// Returns { status: "complete", balances, currency } or { status: "incomplete" }.
function parseConnection(folded, field, op, displayCurrency) {
  const viewer = folded && folded.data && folded.data.viewer;
  if (!viewer) return { status: "incomplete" };
  const conn = viewer[field];
  if (!conn || !Array.isArray(conn.edges)) return { status: "incomplete" };

  // The whole account is denominated in one fiat — the currency we REQUEST
  // (nativeCurrency), which is also the basis Coinbase computes `notional`
  // (totalBalanceFiat) in. So `displayCurrency` is applied uniformly to every
  // row, crypto and cash alike. Fallback (no requested currency supplied, e.g.
  // pure parse tests): crypto reports its own currency (default "USD"); cash
  // has none.
  let currency = displayCurrency || null;
  if (!currency && field === "cryptoAssets") {
    const unreal = (((viewer.oneDayCryptoPerformance || {}).returns || {}).unrealized || {});
    currency = (unreal.value || {}).currency || "USD";
  }

  const now = new Date().toISOString();
  const balances = [];
  for (const edge of conn.edges) {
    const node = edge && edge.node;
    const asset = node && node.asset && node.asset.asset;
    if (!asset || typeof asset !== "object") continue; // drops TiersCurrency/malformed
    const amount = (node.totalBalanceCrypto || {}).amount || "0";
    const notional = (node.totalBalanceFiat || {}).amount || "0";
    if (parseFloat(amount) === 0 && parseFloat(notional) === 0) continue;
    let staked = null;
    if (field === "cryptoAssets") {
      const s = ((node.asset.staking || {}).summary || {}).totalStakedPercent;
      if (s != null) staked = String(parseFloat(s) * 100);
    }
    balances.push({
      key: asset.displaySymbol || asset.platformName || "",
      label: asset.name || "",
      amount: amount,
      notional: notional,
      currency: currency,
      totalStakedPercent: staked,
      precision: null,
      extractedAt: now
    });
  }
  return { status: "complete", balances: balances, currency: currency };
}

function isChallenge(status, headers, bodyText) {
  if (status === 403 || status === 429) return true;
  if (headers && headers.get && headers.get("cf-mitigated")) return true;
  if (typeof bodyText === "string" && bodyText.indexOf("_cf_chl_opt") !== -1) return true;
  if (typeof window !== "undefined" && window._cf_chl_opt) return true;
  if (typeof document !== "undefined" && document.querySelector('div[class="ch-title-zone"]')) return true;
  return false;
}

// Bounded fetch: aborts after timeoutMs so a stalled/throttled request fails
// fast instead of hanging until the native runner's outer ceiling. An abort
// surfaces as a retryable BALANCES_INDETERMINATE rather than an opaque hang.
async function fetchWithTimeout(url, opts, timeoutMs, op) {
  const controller = new AbortController();
  const timer = setTimeout(function () { controller.abort(); }, timeoutMs);
  try {
    return await fetch(url, Object.assign({}, opts, { signal: controller.signal }));
  } catch (e) {
    if (e && e.name === "AbortError") {
      throw new Error("BALANCES_INDETERMINATE: " + op + " — replay timed out after " + timeoutMs + "ms");
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

const REPLAY_TIMEOUT_MS = 8000;

async function replay(spec) {
  const base = "https://www.coinbase.com/graphql/query?operationName=" + encodeURIComponent(spec.operationName);
  // Persisted-query GET.
  const ext = encodeURIComponent(JSON.stringify({ persistedQuery: { version: 1, sha256Hash: spec.sha256Hash } }));
  const vars = encodeURIComponent(JSON.stringify(spec.variables || {}));
  const getUrl = base + "&variables=" + vars + "&extensions=" + ext;
  // Coinbase's GraphQL enforces Apollo CSRF prevention: a "simple" CORS request
  // (only `accept`) is rejected with HTTP 400 "potential Cross-Site Request
  // Forgery". Sending a non-empty `x-apollo-operation-name` (and
  // `apollo-require-preflight`) marks the request non-simple and satisfies the
  // check — this is what Coinbase's own SPA sends.
  const headers = {
    accept: "application/json",
    "x-apollo-operation-name": spec.operationName,
    "apollo-require-preflight": "true"
  };
  let resp = await fetchWithTimeout(getUrl, { method: "GET", credentials: "include", headers: headers }, REPLAY_TIMEOUT_MS, spec.operationName);
  let text = await resp.text();
  if (isChallenge(resp.status, resp.headers, text)) { throw new Error("CHALLENGE_PRESENT"); }
  let folded = decodeBody(text, resp.headers.get("content-type"));
  const notFound = folded && folded.errors &&
    folded.errors.some(e => (e.message || "").indexOf("PersistedQueryNotFound") !== -1 ||
                            (e.extensions || {}).code === "PERSISTED_QUERY_NOT_FOUND");
  if (folded && !notFound) return { folded: folded, headers: resp.headers, status: resp.status };

  // POST fallback (full document). Only possible when a `query` document was
  // harvested. The captured session used persisted GET exclusively, so `query`
  // is currently null — in that case the persisted hash has rotated and the
  // constants file must be refreshed (re-run the probe / Task 0).
  if (!spec.query) {
    throw new Error("BALANCES_INDETERMINATE: " + spec.operationName + " — could not load a complete response");
  }
  resp = await fetchWithTimeout(base, {
    method: "POST",
    credentials: "include",
    headers: {
      "content-type": "application/json",
      accept: "application/json",
      "x-apollo-operation-name": spec.operationName,
      "apollo-require-preflight": "true"
    },
    body: JSON.stringify({ operationName: spec.operationName, query: spec.query, variables: spec.variables || {} })
  }, REPLAY_TIMEOUT_MS, spec.operationName);
  text = await resp.text();
  if (isChallenge(resp.status, resp.headers, text)) throw new Error("CHALLENGE_PRESENT");
  folded = decodeBody(text, resp.headers.get("content-type"));
  return { folded: folded, headers: resp.headers, status: resp.status };
}

// Replays every op in `params.ops` (or the single legacy `params.op`) from the
// SAME already-loaded page and concatenates their balances. Replaying both
// CryptoQuery and CashQuery in one page load — instead of one navigation per
// op — is the core of the captcha-reduction change: a single warm page means a
// single Cloudflare handshake rather than back-to-back cold loads.
//
// This script assumes any Cloudflare challenge has ALREADY cleared: the native
// runner gates evaluation on challenge clearance (waitForChallengeClearance) so
// it survives Cloudflare's post-solve page reload. So here we just replay.
async function run(params, queries) {
  const ops = (params && Array.isArray(params.ops) && params.ops.length)
    ? params.ops
    : (params && params.op ? [params.op] : []);
  if (ops.length === 0) throw new Error("BALANCES_INDETERMINATE: no ops — could not load a complete response");
  const balances = [];
  for (const op of ops) {
    const spec = queries && queries[op];
    if (!spec) throw new Error("BALANCES_INDETERMINATE: " + op + " — could not load a complete response");
    const replayed = await replay(spec);
    // The account display currency we requested for this op — applied to every
    // row so crypto and cash are labeled consistently (e.g. all USD).
    const displayCurrency = (spec.variables && (spec.variables.nativeCurrency || spec.variables.currencyString)) || null;
    const parsed = parseConnection(replayed.folded, spec.field, op, displayCurrency);
    if (parsed.status !== "complete") {
      throw new Error("BALANCES_INDETERMINATE: " + op + " — could not load a complete response");
    }
    for (const b of parsed.balances) balances.push(b);
  }
  return { balances: balances };
}

// Test bridge: expose pure logic to Node without breaking classic-script eval.
// In Node (vm shim) `module` is provided, so we publish exports and bail; the
// IIFE's return value is unused there.
if (typeof module !== "undefined" && module.exports) {
  module.exports = { foldMultipart, parseConnection, deepMerge, run };
  return null;
}

// WebView entry point: the IIFE evaluates to this Promise, which the native
// runner awaits. `params` IS interpolated into the evaluated source as a JS
// variable by the native runner (Android has no WebKit callAsyncJavaScript
// equivalent); the splice is safe only because the value is org.json-escaped
// before injection (JSONObject.toString()) — see buildPromiseWrapper /
// AUTH-3441 (CWE-94). The query catalogue is trusted, compile-time-constant code
// injected ahead of this file, so it rides on the window global.
return run((typeof params !== "undefined" && params) || {}, window.__zhCoinbaseQueries || {});
})();
