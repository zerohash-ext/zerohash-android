// Loads coinbase-screens.js against a small fake DOM tree: the other shims match
// selectors by exact string and cannot model ancestry, opacity or layout.
// Unmodelled selectors throw, so a detector change cannot pass silently.
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const SRC = [
  "../../../Sources/ZerohashSDK/Platforms/Coinbase/coinbase-screens.js",
  "../../../Sources/ConnectSDK/Platforms/Coinbase/coinbase-screens.js",
  "../../../zerohashsdk/src/main/assets/automation/coinbase-screens.js",
  "../../../connectsdk/src/main/assets/automation/coinbase-screens.js"
]
  .map((rel) => fileURLToPath(new URL(rel, import.meta.url)))
  .find(existsSync);
if (!SRC) throw new Error("screens-gate-shim: coinbase-screens.js not found in any known layout");
const SOURCE = readFileSync(SRC, "utf8");

const VIEWPORT = { width: 400, height: 800 };

export function el(o = {}, ...children) {
  const node = {
    tag: o.tag || "div",
    testid: o.testid || null,
    role: o.role || null,
    ariaModal: o.ariaModal || null,
    src: o.src || null,
    style: { opacity: "1", display: "block", visibility: "visible", overflow: "visible", overflowY: "visible", ...(o.style || {}) },
    rect: { top: 100, left: 100, width: 48, height: 48, ...(o.rect || {}) },
    parentElement: null,
    children
  };
  for (const c of children) c.parentElement = node;
  node.getBoundingClientRect = () => ({ ...node.rect, bottom: node.rect.top + node.rect.height, right: node.rect.left + node.rect.width });
  node.closest = (sel) => {
    const match = CLOSEST[sel];
    if (!match) throw new Error("screens-gate-shim: closest() does not model " + sel);
    for (let n = node; n; n = n.parentElement) if (match(n)) return n;
    return null;
  };
  return node;
}

const OUTAGE_SEL = 'img[src*="/ui-infra/illustration/"][src*="/pictogram/svg/"][src*="/outage-"]';
function matcher(sel) {
  if (sel === OUTAGE_SEL) {
    return (n) => n.tag === "img" && !!n.src && ["/ui-infra/illustration/", "/pictogram/svg/", "/outage-"].every((p) => n.src.includes(p));
  }
  const exact = /^\[data-testid="([^"]+)"\]$/.exec(sel);
  if (exact) return (n) => n.testid === exact[1];
  throw new Error("screens-gate-shim: querySelectorAll() does not model " + sel);
}

const POST_SUBMIT_IDS = new Set([
  "step-twoFactorStep-active", "step-statusStep-active", "step-riskSelfServeStep-active",
  "step-systemCancellationStep-active", "fallback"
]);
const CLOSEST = {
  '[role="dialog"][aria-modal="true"]': (n) => n.role === "dialog" && n.ariaModal === "true",
  '[data-testid^="step-"][data-testid$="-inactive"]': (n) => !!n.testid && n.testid.startsWith("step-") && n.testid.endsWith("-inactive"),
  '[data-testid="step-twoFactorStep-active"], [data-testid="step-statusStep-active"], [data-testid="step-riskSelfServeStep-active"], [data-testid="step-systemCancellationStep-active"], [data-testid="fallback"]':
    (n) => POST_SUBMIT_IDS.has(n.testid)
};

function walk(root, fn, out = []) {
  if (fn(root)) out.push(root);
  for (const c of root.children) walk(c, fn, out);
  return out;
}

export const OUTAGE_LIGHT = "https://static-assets.coinbase.com/ui-infra/illustration/v1/pictogram/svg/light/outage-2.svg";
export const OUTAGE_DARK = "https://static-assets.coinbase.com/ui-infra/illustration/v1/pictogram/svg/dark/outage-3.svg";
const full = { top: 0, left: 0, ...VIEWPORT };
export const outageImg = (o = {}) => el({ tag: "img", src: OUTAGE_LIGHT, ...o });
export const dialog = (...children) => el({ role: "dialog", ariaModal: "true", rect: full }, ...children);
export const step = (testid, ...children) => el({ testid, rect: full }, ...children);

// `setBody` swaps the page between polls.
export function loadGate(...body) {
  const breadcrumbs = [];
  const documentElement = el({ tag: "html", rect: full });
  const doc = {
    documentElement,
    querySelectorAll(sel) { return walk(this.body, matcher(sel)); },
    querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
  };
  const setBody = (...children) => {
    doc.body = el({ tag: "body", rect: full }, ...children);
    doc.body.parentElement = documentElement;
  };
  setBody(...body);
  const window = {
    innerWidth: VIEWPORT.width,
    innerHeight: VIEWPORT.height,
    getComputedStyle: (n) => n.style,
    __zhTelemetry: { breadcrumb: (phase, note) => breadcrumbs.push(`${phase}:${note}`) }
  };
  vm.runInNewContext(SOURCE, { window, document: doc, setTimeout, clearTimeout, console });
  return { gate: window.__zhCoinbaseScreens, breadcrumbs, setBody };
}

// The real guard, to borrow onto a fake registry: it reads detection from `this`.
export function realGuard() {
  return loadGate().gate.guard;
}
