// Single-shot Coinbase Receive-flow automation. Evaluated by the visible runner
// after settling on www.coinbase.com. Reads params from the bound argument
// `params` = { asset, network, amount }. On Android `params` IS interpolated into
// the evaluated source as a JS variable by the native runner (Android has no
// WebKit callAsyncJavaScript equivalent); the splice is safe only because the
// value is org.json-escaped before injection (JSONObject.toString()) — see
// buildPromiseWrapper / AUTH-3441 (CWE-94). Resolves with a
// DepositAddressResult-shaped object, or rejects with a tagged Error whose
// message the native side maps to a platform-threw error.
(function () {
  var P = (typeof params !== "undefined" && params) || {};
  var ASSET = String(P.asset || "").trim();
  var NETWORK = P.network ? String(P.network).trim().toLowerCase() : null;
  var AMOUNT = P.amount || null; // { value, currency } | null

  // Coinbase mobile-web Receive entry (see openReceiveModal): the global
  // actions CTA opens a tray; the "Receive crypto" row has no testid, so we
  // anchor on its down-arrow icon. Clicking it advances to the asset selector.
  var GLOBAL_ACTIONS_CTA = '[data-testid="global-actions-cta-wrapper"]';
  var RECEIVE_ROW_ICON = '[data-icon-name="arrowDown"]';
  var ASSET_SELECTION_STEP = '[data-testid="step-assetSelection-active"]';
  var NETWORK_WARNING_UNDERSTAND = '[data-testid="network-warning-step-understand"]';
  var LIGHTNING_NUX_STEP = '[data-testid="step-lightningReceiveNuxStep-active"]';
  var AMOUNT_ENTRY_STEP = '[data-testid$="AmountEntryStep-active"]';
  var NETWORK_SELECTION_STEP = '[data-testid="step-networkSelection-active"]';
  // Memo/destination-tag warning shown before the address for memo-required
  // networks (e.g. XRP "É necessário um memo"). Its confirm button has no
  // testid, but it is the only primary button inside this step container.
  var MEMO_WARNING_STEP = '[data-testid="step-assetDisplay-active"]';
  var RECEIVE_ADDRESS_TOGGLE = '[data-testid="receive-address-toggle"]';
  // Memo/destination-tag value on the address screen. The value renders in a
  // span whose styled-component class contains "AddressMemoText" (the hash
  // suffix changes between Coinbase builds, so match the stable prefix). Absent
  // for networks that don't require a memo.
  var MEMO_VALUE = '[class*="AddressMemoText"]';
  var PRIMARY_SUBMIT = 'button[data-variant="primary"]';
  var BOLT11_RE = /ln(bc|tb)[a-z0-9]{40,}/i;

  // Mobile selectors confirmed by live recording on coinbase.com mobile web:
  // the asset cell is `ReceiveAssetSelectorCell-<ASSET>` and the network row is
  // `<network>-network` (NOT the desktop `*-cell-pressable` variants).
  function assetCell(a) { return '[data-testid="ReceiveAssetSelectorCell-' + a + '"]'; }
  function networkCell(n) { return '[data-testid="' + n + '-network"]'; }

  var DEADLINE = Date.now() + 15000;
  function timeLeft() { return DEADLINE - Date.now(); }

  // Generic DOM/timing helpers, injected via window.__zhDom (see dom-helpers.js,
  // prepended by Coinbase.swift). Bound to short locals to keep call sites terse.
  var D = window.__zhDom;
  var sleep = D.sleep;
  var $ = D.$;
  var realisticClick = D.realisticClick;
  var findButtonByText = D.findButtonByText;
  var clickableAncestor = D.clickableAncestor;
  // waitUntil/waitFor now take an explicit deadline; wrap to pass this run's DEADLINE.
  function waitUntil(find, timeoutMs) { return D.waitUntil(find, timeoutMs, DEADLINE); }
  function waitFor(sel, timeoutMs) { return D.waitFor(sel, timeoutMs, DEADLINE); }

  async function openReceiveModal() {
    // Coinbase mobile web has no Receive quick-action tile. The Receive flow is
    // reached by opening the "global actions" CTA, which presents a tray whose
    // "Receive crypto" row is a generated-class div with no testid — its only
    // stable, locale-proof anchor is the down-arrow icon (RECEIVE_ROW_ICON).
    // After clicking it the page advances to step-assetSelection-active, where
    // the existing ReceiveAssetSelectorCell-* selectors take over.
    //
    // Each stage throws a distinct error suffix so a failure log pinpoints
    // which step broke (CTA absent vs tray row absent vs step didn't advance).
    var cta = await waitUntil(function () { return $(GLOBAL_ACTIONS_CTA); }, 10000);
    if (!cta) throw new Error("receive_entry_not_found:cta");
    // The CTA wrapper is a <div>; the real interactive element is the button
    // inside it (matches the manual repro). Click the inner control, not the div.
    var ctaBtn = cta.querySelector("button, [role='button'], a") || cta;
    realisticClick(ctaBtn);

    var icon = await waitUntil(function () { return $(RECEIVE_ROW_ICON); }, 5000);
    if (!icon) throw new Error("receive_entry_not_found:row");
    realisticClick(clickableAncestor(icon));

    var step = await waitUntil(function () { return $(ASSET_SELECTION_STEP); }, 6000);
    if (!step) throw new Error("receive_entry_not_found:step");
  }

  async function pickAsset() {
    var sel = assetCell(ASSET);
    var el = await waitFor(sel, 6000).catch(function () { return null; });
    if (!el) throw new Error("asset_not_available:" + ASSET);
    realisticClick(el);
  }

  // Scrapes the network-selection screen for the canonical network ids it
  // currently offers. Each option cell has a testid of the form
  // "<id>-network" (e.g. "solana-network"); we strip the suffix to recover the
  // id the caller would pass as `network`. Ids are lower-cased to match the
  // normalized `NETWORK` (see line 9) so a non-lowercase testid can't cause a
  // silent miss. Returns a de-duplicated array, order-preserving. Empty if no
  // option cells are present.
  function availableNetworks() {
    var cells = document.querySelectorAll('[data-testid$="-network"]');
    var ids = [];
    for (var i = 0; i < cells.length; i++) {
      var tid = cells[i].getAttribute("data-testid") || "";
      var id = tid.replace(/-network$/, "").toLowerCase();
      if (id && ids.indexOf(id) === -1) ids.push(id);
    }
    return ids;
  }

  // Finds the network option cell for `n` (already lower-cased). The fast path
  // is the exact lowercased testid; the fallback scans every `-network` cell and
  // compares ids case-insensitively, so a non-lowercase testid (e.g.
  // "Solana-network") can't cause a silent click miss. Returns null if absent.
  function findNetworkCell(n) {
    var exact = $(networkCell(n));
    if (exact) return exact;
    var cells = document.querySelectorAll('[data-testid$="-network"]');
    for (var i = 0; i < cells.length; i++) {
      var tid = cells[i].getAttribute("data-testid") || "";
      if (tid.replace(/-network$/, "").toLowerCase() === n) return cells[i];
    }
    return null;
  }

  async function pickNetworkIfNeeded() {
    if (!NETWORK) return;

    // Case 1: the requested network is offered — click it. Poll briefly because
    // the network-selection screen may not have rendered yet.
    var el = await waitUntil(function () { return findNetworkCell(NETWORK); }, 4000);
    if (el) { realisticClick(el); return; }

    // Distinguish "no network screen" (single-network asset; skip) from
    // "network screen is showing but our network isn't listed" (real error).
    var selectionVisible = !!$(NETWORK_SELECTION_STEP) ||
      document.querySelectorAll('[data-testid$="-network"]').length > 0;

    // Case 3: no selection screen appeared — tolerate (single-network asset).
    if (!selectionVisible) return;

    // Case 2: selection screen is up but our network isn't among the options.
    var ids = availableNetworks();
    var msg = "network '" + NETWORK + "' not available for " + ASSET + " on coinbase.";
    if (ids.length > 0) msg += " Available networks: " + ids.join(", ") + ".";
    throw new Error(msg);
  }

  function dismissInterstitials() {
    // Fire-and-forget; warnings/NUX may or may not appear.
    var nux = $(LIGHTNING_NUX_STEP);
    if (nux) { var c = findButtonByText("Continue"); if (c) realisticClick(c); }
    var understand = $(NETWORK_WARNING_UNDERSTAND);
    if (understand) realisticClick(understand);
    // Memo/destination-tag warning (e.g. XRP). Click the step's primary button
    // (the confirm has no testid). Scoped to the step so we never click a
    // primary button on the address screen. The "don't show again" checkbox is
    // left untouched.
    var memo = $(MEMO_WARNING_STEP);
    if (memo) {
      var memoBtn = memo.querySelector('button[data-variant="primary"]');
      if (memoBtn) realisticClick(memoBtn);
    }
  }

  // Reads the on-chain address straight from the DOM. On Coinbase mobile the
  // address is rendered as the *text* of the receive-address-toggle button
  // (data-testid="receive-address-toggle"), e.g.
  // "9G2j49zARUDCCQi1Me7KsahAzNydKSA17niMZt4rvMTi". We click it ONCE (guarded by
  // `addressToggleClicked`) to expand the full address, then read textContent.
  // Lightning invoices still surface as a BOLT11 inside that text.
  var addressToggleClicked = false;
  // Reads the required memo / destination-tag from the address screen, if one
  // is shown. Memo-required networks (e.g. XRP) render it in a span whose class
  // contains "AddressMemoText"; non-memo networks have no such element. Scoped
  // to the address step when present so we never read unrelated text. Returns
  // the trimmed value, or "" when absent/implausible.
  function readMemo() {
    var scope = $(MEMO_WARNING_STEP) || document;
    var el = scope.querySelector(MEMO_VALUE);
    if (!el) return "";
    var memo = (el.textContent || "").trim();
    if (!memo) return "";
    if (!/^[A-Za-z0-9:_-]{1,64}$/.test(memo)) return "";
    return memo;
  }

  function readAddressFromDOM() {
    var toggle = $(RECEIVE_ADDRESS_TOGGLE);
    if (!toggle) return null;
    // One-time click to ensure the full (non-truncated) address is shown.
    // Guarded so the run loop doesn't re-click it every iteration.
    if (!addressToggleClicked) {
      realisticClick(toggle);
      addressToggleClicked = true;
    }
    var text = (toggle.textContent || "").trim();
    if (!text) return null;
    // Lightning: a BOLT11 anywhere in the text.
    var bolt = text.match(BOLT11_RE);
    if (bolt) {
      return { address: bolt[0], destinationTag: "", depositUri: "lightning:" + bolt[0] };
    }
    // On-chain: the button text IS the address. Sanity-check it looks like an
    // address token (no whitespace, plausible length) before accepting it.
    if (/^[A-Za-z0-9:_-]{20,120}$/.test(text)) {
      return { address: text, destinationTag: readMemo(), depositUri: "" };
    }
    return null;
  }

  async function fillAmountAndSubmit() {
    var input = document.querySelector("input[inputmode], input[type='text']");
    if (input && AMOUNT) {
      input.focus();
      // Use the native value setter so React picks up the change.
      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
      setter.call(input, AMOUNT.value);
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    }
    var submit = $(PRIMARY_SUBMIT);
    if (submit) realisticClick(submit);
  }

  async function run() {
    if (!ASSET) throw new Error("missing_asset");
    await openReceiveModal();
    await pickAsset();
    await pickNetworkIfNeeded();

    // Race loop: poll for the rendered address, amount-entry step, or deadline.
    // Guard so we fill+submit the amount exactly once: the amount-entry step can
    // linger across several poll passes after the first submit, and re-clicking
    // the primary button risks a double-submitted invoice request.
    var amountWasSubmitted = false;
    while (timeLeft() > 0) {
      dismissInterstitials();

      if ($(AMOUNT_ENTRY_STEP) && !amountWasSubmitted) {
        if (!AMOUNT) throw new Error("requires an amount");
        await fillAmountAndSubmit();
        amountWasSubmitted = true;
        await sleep(500);
      }

      var parsed = readAddressFromDOM();

      if (parsed && parsed.address) {
        var warnings = [];
        var amountSubmitted = AMOUNT
          ? { value: AMOUNT.value, requestedCurrency: AMOUNT.currency, resolvedSymbol: ASSET }
          : undefined;
        var result = {
          address: parsed.address,
          destinationTag: parsed.destinationTag || "",
          network: NETWORK || "",
          asset: ASSET,
          warnings: warnings,
          depositUri: parsed.depositUri || "",
        };
        if (amountSubmitted) result.amountSubmitted = amountSubmitted;
        return result;
      }
      await sleep(250);
    }
    throw new Error("timeout");
  }

  return run();
})();
