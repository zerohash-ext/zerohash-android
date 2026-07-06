// Coinbase withdraw — injected automation.
//
// Installs `window.__zhWithdraw = { start, continue, cancel }` (idempotent). The
// native side injects this file and then calls the relevant entry point; each
// returns a Promise of a WithdrawState-shaped object (or { cancelled } for
// cancel), awaited via callAsyncJavaScript on the SDK side.
(function () {
  if (window.__zhWithdraw) return; // idempotent across re-injection

  // ─── Selectors (Coinbase data-testids) ──────────────────────────────

  // Active step indicator.
  var STEP_ACTIVE = '[data-testid^="step-"][data-testid$="-active"]';

  // Send modal entry + recipient
  var QUICK_ACTION_SEND = '[data-testid="quick-action-send"]';
  var TRANSFER_DROPDOWN_BUTTON = '[data-testid="transfer-dropdown-button"]';
  var TRANSACTIONS_TAB = 'button[data-testid="Transactions-Tab"]';
  var TRANSFER_DROPDOWN = 'div[class*="cds-dropdown"]';
  var TRANSFER_DROPDOWN_BUTTON_IN_MENU = TRANSFER_DROPDOWN + ' button';
  // Advance mobile: Transactions tab → global-actions CTA → BottomDrawer transfer
  var GLOBAL_ACTIONS_CTA_BUTTON = "div[data-testid='global-actions-cta-wrapper'] button";
  var BOTTOM_DRAWER_BUTTON = "div[class*='BottomDrawer'] button";
  var RECIPIENT_INPUT = '[data-testid="recipient-search-input"]';

  // Conditional recipient-type chooser
  var STEP_SELECT_RECIPIENT_TYPE = '[data-testid="step-selectRecipientType-active"]';
  var SELF_CUSTODY_OPTION = '[data-testid="self-custody-option"]';
  var EXCHANGE_OPTION = '[data-testid="exchange-option"]';

  // Coin selection
  var COIN_LIST = '[data-testid^="send-asset-selector-cell-"][data-testid$="-cell-pressable"]';
  function coinDirect(ticker) {
    return '[data-testid="send-asset-selector-cell-' + String(ticker).toUpperCase() + '-cell-pressable"]';
  }

  // Network selection
  var NETWORK_ITEMS = '[data-testid$="-cell-pressable"][data-testid^="l2-list-item-"]:not([disabled])';
  var NETWORK_ITEMS_ANY = '[data-testid$="-cell-pressable"][data-testid^="l2-list-item-"]';
  function networkTestId(slug) {
    return '[data-testid="l2-list-item-' + String(slug).toLowerCase() + '-cell-pressable"]';
  }

  // Network-acceptance warning
  var NETWORK_WARNING_CONTINUE = '[data-testid="network-warning-step-understand"]';

  // Destination tag / memo
  var STEP_DESTINATION_TAG = '[data-testid="step-destinationTagStep-active"]';
  var DESTINATION_TAG_INPUT = STEP_DESTINATION_TAG + ' input';
  var SKIP_DESTINATION_TAG = '[data-testid="skip-destination-tag"]';

  // Risk-engine ID/liveness verification (current send)
  var STEP_RISK_VERIFICATION = '[data-testid="step-riskSelfServeStep-active"]';
  var RISK_START_ID_CHECK_LABEL = "Start ID check";
  var RISK_CANCEL_TRANSFER_LABEL = "Cancel transfer";
  var RISK_COMPLETE_BEFORE_LABEL = "Complete before";

  // "Review pending transfer" — a PRIOR transfer blocking a new send
  var STEP_PREVIOUS_TRANSFER = '[data-testid="step-previousTransfer-active"]';
  var PENDING_AMOUNT_LABEL = "Amount";
  var PENDING_TO_LABEL = "To";

  // Terminal "Transfer canceled" screen
  var STEP_USER_CANCELLATION = '[data-testid="step-userCancellationSuccess-active"]';

  // Amount entry
  var CURRENCY_INPUT = '[data-testid="currency-input"]';
  var MAX_BUTTON = '[data-testid="max-button"]';
  var PREVIEW_SEND = '[data-testid="preview-send-button"]';
  var ASSET_BALANCE = '[data-testid="asset-balance-cell"]';

  // Travel rule (FATF) form
  var BENEFICIARY_NAME = '[data-testid="beneficiary-full-name"]';
  var COUNTRY_SELECT = '[data-testid="country-select"]';
  function countryOption(cc) { return '[data-testid="country-option-' + cc + '"]'; }
  var SUBMIT_BUTTON = '[data-testid="submit-button"]';

  // Transfer details (purpose + relationship) form
  var TRANSFER_PURPOSE = '[data-testid="transfer-purpose-select"]';
  var TRANSFER_RELATIONSHIP = '[data-testid="relationship-with-beneficiary-select"]';
  var TRANSFER_SUBMIT = '[data-testid="transfer-details-step-submit-container"] button';
  var DROPDOWN_OPTION_SELECTORS = ['[role="option"]', '[role="menuitem"]', '[data-testid*="option"]'];

  // Preview + confirm
  var SEND_NOW = '[data-testid="send-now-button"]';
  var SEND_PREVIEW_FIAT = '[data-testid="send-preview-fiat-header"]';
  var SEND_PREVIEW_CRYPTO = '[data-testid="send-preview-crypto-header"]';
  var SEND_PREVIEW_RECIPIENT = '[data-testid="send-preview-recipient-container"]';
  var SEND_PREVIEW_NETWORK = '[data-testid="send-preview-network"]';
  var SEND_PREVIEW_TIME = '[data-testid="time-estimate"]';
  var SEND_PREVIEW_FEE = '[data-testid="send-preview-footer-network-fee-explainer"]';
  var AMOUNT_ERROR_MESSAGE = '[data-testid="error-message"]';

  // 2FA / loading / success
  var IDENTITY_ACCESS_WRAPPER = '[data-testid="identity-access-view-wrapper"]';
  var STATUS_LOADING = '[data-testid="status-animation-loading"]';
  var TWO_FACTOR_TOTP = '[data-testid="two-factor-button-TOTP"]';
  var TWO_FACTOR_SMS = '[data-testid="two-factor-button-SMS"]';
  var PASSKEY_PROMPT = '[data-testid="passkey-auth"], [data-testid="passkey-verify-button"]';
  var OTP_INPUT = "#one-time-code";
  var OTP_CONTAINER = '[data-testid="code-inputs-container"]';
  var SEND_SUCCESS = '[data-testid="send-success-content"]';
  var SUCCESS_HEADLINE = '[data-testid="send-success-content-headline"]';
  var STATUS_COMPLETE_BTN = '[data-testid="status-step-complete-button"]';
  var MODAL_OVERLAY = '[data-testid="modal-overlay"]';

  // Post-send transaction-details step
  var STEP_TRANSACTION_DETAILS = '[data-testid="step-transactionDetailsStep-active"]';

  // Keep references so the scaffold's selector set isn't dead-code-eliminated and
  // is available to the driver functions ported in later steps.
  var SEL = {
    STEP_ACTIVE: STEP_ACTIVE,
    QUICK_ACTION_SEND: QUICK_ACTION_SEND,
    TRANSFER_DROPDOWN_BUTTON: TRANSFER_DROPDOWN_BUTTON,
    TRANSACTIONS_TAB: TRANSACTIONS_TAB,
    TRANSFER_DROPDOWN: TRANSFER_DROPDOWN,
    TRANSFER_DROPDOWN_BUTTON_IN_MENU: TRANSFER_DROPDOWN_BUTTON_IN_MENU,
    GLOBAL_ACTIONS_CTA_BUTTON: GLOBAL_ACTIONS_CTA_BUTTON,
    BOTTOM_DRAWER_BUTTON: BOTTOM_DRAWER_BUTTON,
    RECIPIENT_INPUT: RECIPIENT_INPUT,
    STEP_SELECT_RECIPIENT_TYPE: STEP_SELECT_RECIPIENT_TYPE,
    SELF_CUSTODY_OPTION: SELF_CUSTODY_OPTION,
    EXCHANGE_OPTION: EXCHANGE_OPTION,
    COIN_LIST: COIN_LIST,
    coinDirect: coinDirect,
    NETWORK_ITEMS: NETWORK_ITEMS,
    NETWORK_ITEMS_ANY: NETWORK_ITEMS_ANY,
    networkTestId: networkTestId,
    NETWORK_WARNING_CONTINUE: NETWORK_WARNING_CONTINUE,
    STEP_DESTINATION_TAG: STEP_DESTINATION_TAG,
    DESTINATION_TAG_INPUT: DESTINATION_TAG_INPUT,
    SKIP_DESTINATION_TAG: SKIP_DESTINATION_TAG,
    STEP_RISK_VERIFICATION: STEP_RISK_VERIFICATION,
    RISK_START_ID_CHECK_LABEL: RISK_START_ID_CHECK_LABEL,
    RISK_CANCEL_TRANSFER_LABEL: RISK_CANCEL_TRANSFER_LABEL,
    RISK_COMPLETE_BEFORE_LABEL: RISK_COMPLETE_BEFORE_LABEL,
    STEP_PREVIOUS_TRANSFER: STEP_PREVIOUS_TRANSFER,
    PENDING_AMOUNT_LABEL: PENDING_AMOUNT_LABEL,
    PENDING_TO_LABEL: PENDING_TO_LABEL,
    STEP_USER_CANCELLATION: STEP_USER_CANCELLATION,
    CURRENCY_INPUT: CURRENCY_INPUT,
    MAX_BUTTON: MAX_BUTTON,
    PREVIEW_SEND: PREVIEW_SEND,
    ASSET_BALANCE: ASSET_BALANCE,
    BENEFICIARY_NAME: BENEFICIARY_NAME,
    COUNTRY_SELECT: COUNTRY_SELECT,
    countryOption: countryOption,
    SUBMIT_BUTTON: SUBMIT_BUTTON,
    TRANSFER_PURPOSE: TRANSFER_PURPOSE,
    TRANSFER_RELATIONSHIP: TRANSFER_RELATIONSHIP,
    TRANSFER_SUBMIT: TRANSFER_SUBMIT,
    DROPDOWN_OPTION_SELECTORS: DROPDOWN_OPTION_SELECTORS,
    SEND_NOW: SEND_NOW,
    SEND_PREVIEW_FIAT: SEND_PREVIEW_FIAT,
    SEND_PREVIEW_CRYPTO: SEND_PREVIEW_CRYPTO,
    SEND_PREVIEW_RECIPIENT: SEND_PREVIEW_RECIPIENT,
    SEND_PREVIEW_NETWORK: SEND_PREVIEW_NETWORK,
    SEND_PREVIEW_TIME: SEND_PREVIEW_TIME,
    SEND_PREVIEW_FEE: SEND_PREVIEW_FEE,
    AMOUNT_ERROR_MESSAGE: AMOUNT_ERROR_MESSAGE,
    IDENTITY_ACCESS_WRAPPER: IDENTITY_ACCESS_WRAPPER,
    STATUS_LOADING: STATUS_LOADING,
    TWO_FACTOR_TOTP: TWO_FACTOR_TOTP,
    TWO_FACTOR_SMS: TWO_FACTOR_SMS,
    PASSKEY_PROMPT: PASSKEY_PROMPT,
    OTP_INPUT: OTP_INPUT,
    OTP_CONTAINER: OTP_CONTAINER,
    SEND_SUCCESS: SEND_SUCCESS,
    SUCCESS_HEADLINE: SUCCESS_HEADLINE,
    STATUS_COMPLETE_BTN: STATUS_COMPLETE_BTN,
    MODAL_OVERLAY: MODAL_OVERLAY,
    STEP_TRANSACTION_DETAILS: STEP_TRANSACTION_DETAILS
  };

  // ─── Withdraw-local DOM/input helpers ───────────────────────────────
  //
  // Build on the SDK's shared window.__zhDom (injected before this file). Kept
  // withdraw-local so the shared dom-helpers.js (used by deposit/balance) is
  // untouched; promote to shared later if another platform needs them.
  var D = window.__zhDom;

  function isVisible(el) {
    if (!el) return false;
    if (el.offsetParent !== null) return true;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  // First VISIBLE element matching `sel`, or null.
  function queryVisible(sel) {
    var nodes = document.querySelectorAll(sel);
    for (var i = 0; i < nodes.length; i++) {
      if (isVisible(nodes[i])) return nodes[i];
    }
    return null;
  }

  // Poll until any selector matches a visible element; resolve the matched
  // SELECTOR STRING (so callers can branch on `which === SEL.X`), null on timeout.
  function waitForAny(selectors, timeoutMs) {
    timeoutMs = timeoutMs || 15000;
    var end = Date.now() + timeoutMs;
    return new Promise(function (resolve) {
      (function poll() {
        for (var i = 0; i < selectors.length; i++) {
          if (queryVisible(selectors[i])) return resolve(selectors[i]);
        }
        if (Date.now() >= end) return resolve(null);
        setTimeout(poll, 150);
      })();
    });
  }

  // Poll `fn` until it returns truthy; reject with `errMsg` on timeout.
  function pollUntil(fn, timeoutMs, errMsg) {
    timeoutMs = timeoutMs || 15000;
    var end = Date.now() + timeoutMs;
    return new Promise(function (resolve, reject) {
      (function poll() {
        var v;
        try { v = fn(); } catch (e) { v = null; }
        if (v) return resolve(v);
        if (Date.now() >= end) return reject(new Error(errMsg || "pollUntil timed out"));
        setTimeout(poll, 150);
      })();
    });
  }

  // Resolve the element for `sel` or reject on timeout (delegates to __zhDom).
  function waitForElement(sel, timeoutMs) {
    return D.waitFor(sel, timeoutMs);
  }

  // Poll for a button/clickable whose text matches `text` (exact or contains).
  function waitForButtonByText(text, opts) {
    opts = opts || {};
    var root = opts.root || document;
    var match = opts.match || "exact";
    var requireEnabled = opts.requireEnabled || false;
    var timeoutMs = opts.timeoutMs || 5000;
    var want = String(text).toLowerCase();
    return pollUntil(function () {
      var btns = root.querySelectorAll("button, [role='button'], a");
      for (var i = 0; i < btns.length; i++) {
        var t = (btns[i].textContent || "").trim().toLowerCase();
        var textMatch = match === "contains" ? t.indexOf(want) !== -1 : t === want;
        // requireEnabled: keep waiting while the matched button is disabled
        // (Coinbase disables Continue until async field validation settles).
        if (textMatch && !(requireEnabled && isDisabled(btns[i]))) return btns[i];
      }
      return null;
    }, timeoutMs, "withdraw/button-not-found: " + text);
  }

  // Poll for a leaf element whose trimmed text exactly equals `text` (the
  // recipient dropdown renders the pasted address as an item).
  function waitForExactText(text, timeoutMs) {
    var want = String(text).trim();
    return pollUntil(function () {
      var all = document.querySelectorAll("*");
      for (var i = 0; i < all.length; i++) {
        if (all[i].children.length === 0 && (all[i].textContent || "").trim() === want) {
          return all[i];
        }
      }
      return null;
    }, timeoutMs, "withdraw/exact-text-not-found: " + text);
  }

  function humanDelay(ms) { return D.sleep(ms || 0); }
  function humanClick(el) { D.realisticClick(el); return D.sleep(50); }

  // React-controlled inputs ignore a plain `input.value =`. Set via the native
  // prototype descriptor's setter, then dispatch input/change so React's
  // onChange fires. This is the crux of driving Coinbase's fields.
  function setReactValue(input, value) {
    var proto = input.tagName === "TEXTAREA"
      ? window.HTMLTextAreaElement.prototype
      : window.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) { desc.set.call(input, value); }
    else { input.value = value; }
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function typeLikeHuman(input, text) {
    input.focus();
    setReactValue(input, String(text));
    return D.sleep(50);
  }

  function isDisabled(el) {
    return !!(el && (el.disabled || el.getAttribute("aria-disabled") === "true"));
  }

  function getInnerText(el) {
    return el ? (el.innerText || el.textContent || "") : "";
  }

  // Toolbox the driver functions (ported in later sub-steps) build on.
  var H = {
    isVisible: isVisible, queryVisible: queryVisible, waitForAny: waitForAny,
    pollUntil: pollUntil, waitForElement: waitForElement, waitForButtonByText: waitForButtonByText,
    humanDelay: humanDelay, humanClick: humanClick, setReactValue: setReactValue,
    typeLikeHuman: typeLikeHuman, isDisabled: isDisabled, getInnerText: getInnerText
  };

  // ─── Screen drivers ──────────────────────────────────────────────────

  // Coinbase emits a `loaded` wrapper around real steps during transitions;
  // walk in reverse so the innermost (deepest) real step wins over it.
  function readActiveStep() {
    var all = document.querySelectorAll(SEL.STEP_ACTIVE);
    for (var i = all.length - 1; i >= 0; i--) {
      var id = all[i].getAttribute("data-testid") || "";
      var m = id.match(/^step-(.+)-active$/);
      if (m && m[1] !== "loaded") return m[1];
    }
    if (all.length > 0) return "loaded";
    return null;
  }

  // Desktop shows a direct "Send" quick-action; mobile collapses it into a
  // "Transfer" dropdown; Coinbase Advance uses a two-click dropdown. Detect
  // which UI is active, then click through to the recipient field.
  async function openSendModal() {
    var trigger = await pollUntil(function () {
      return queryVisible(SEL.QUICK_ACTION_SEND)
        || queryVisible(SEL.TRANSFER_DROPDOWN_BUTTON)
        || queryVisible(SEL.TRANSACTIONS_TAB)
        || D.findButtonByText("Transfer");
    }, 15000, "withdraw/send-trigger-not-found");

    // The Advance UI surfaces either the transfer dropdown directly or, on
    // mobile, a Transactions tab that must be opened first — openSendModalAdvance
    // handles the tab. The standard UI shows the quick-action "Send"/"Transfer".
    if (trigger.getAttribute("data-testid") === "transfer-dropdown-button"
        || trigger.getAttribute("data-testid") === "Transactions-Tab") {
      await openSendModalAdvance();
    } else {
      await openSendModalStandard();
    }
  }

  async function openSendModalStandard() {
    var triggerButton = await pollUntil(function () {
      return queryVisible(SEL.QUICK_ACTION_SEND) || D.findButtonByText("Transfer");
    }, 15000, "withdraw/send-trigger-standard-modal-not-found");
    await humanClick(triggerButton);

    // After "Transfer", the layout either surfaces a menu/sheet with a
    // "Send crypto" option, or jumps straight to the recipient field — and the
    // container differs (desktop role="menu" vs mobile sheet). So don't depend
    // on the container: look for the "Send crypto" option anywhere; click it if
    // it appears, otherwise fall through to the recipient wait.
    if (triggerButton.tagName === "BUTTON" && (triggerButton.textContent || "").trim() === "Transfer") {
      var sendCrypto = await waitForButtonByText("Send crypto", { match: "contains", timeoutMs: 4000 })
        .catch(function () { return null; });
      if (sendCrypto) await humanClick(sendCrypto);
    }

    await awaitRecipientOrPendingBlock();
  }

  async function openSendModalAdvance() {
    var transactionsTab = queryVisible(SEL.TRANSACTIONS_TAB);
    await humanClick(transactionsTab);
    var dropdownBtn = await waitForElement(SEL.GLOBAL_ACTIONS_CTA_BUTTON, 5000);
    await humanClick(dropdownBtn);
    // Wait for the first button to appear, then settle before querying all.
    var transferButton = await waitForElement(SEL.BOTTOM_DRAWER_BUTTON);
    await humanDelay(200);
    await humanClick(transferButton);
    await awaitRecipientOrPendingBlock();
  }

  // Tagged error carrying a blocking prior transfer's details. Thrown from
  // awaitRecipientOrPendingBlock and converted to a rejected/pending_transfer
  // state by `start`'s catch.
  function pendingTransferError(details) {
    var e = new Error("withdraw/pending-transfer: a prior transfer needs verification before sending again");
    e.zhPendingTransfer = details;
    return e;
  }

  // Read the prior transfer's Amount / To / Complete-before from the visible
  // "Review pending transfer" screen. All-null when the step isn't up.
  function readPendingTransfer() {
    var step = queryVisible(SEL.STEP_PREVIOUS_TRANSFER);
    if (!step) return { amount: null, recipient: null, completeBefore: null };
    return {
      amount: readLabeledValue(step, SEL.PENDING_AMOUNT_LABEL),
      recipient: readLabeledValue(step, SEL.PENDING_TO_LABEL),
      completeBefore: readLabeledValue(step, SEL.RISK_COMPLETE_BEFORE_LABEL)
    };
  }

  // After the send trigger Coinbase normally shows the recipient field — unless a
  // prior transfer is still pending identity verification, in which case it shows
  // the blocking "Review pending transfer" screen instead. Race the two so we
  // recognize the block immediately rather than timing out on the missing
  // recipient input; on the block, throw with the prior transfer's details.
  async function awaitRecipientOrPendingBlock() {
    // Match the recipient by DOM PRESENCE (querySelector), mirroring the original
    // waitForElement wait — it can mount before our isVisible heuristic considers
    // it visible, so a visibility-gated race would spuriously time out. The block
    // screen is the active step when up, so detect it by visibility.
    var deadline = Date.now() + 15000;
    for (;;) {
      if (document.querySelector(SEL.RECIPIENT_INPUT)) return;
      if (queryVisible(SEL.STEP_PREVIOUS_TRANSFER)) throw pendingTransferError(readPendingTransfer());
      if (Date.now() >= deadline) throw new Error("withdraw/recipient-not-found: " + SEL.RECIPIENT_INPUT);
      await D.sleep(150);
    }
  }

  async function enterRecipient(address) {
    var input = await waitForElement(SEL.RECIPIENT_INPUT, 15000);
    await typeLikeHuman(input, address);
    // Dropdown renders async; wait for the item by its address text, then click.
    var item = await waitForExactText(address, 15000);
    await humanClick(item);
  }

  // Coinbase keeps the previous step container mounted during fade; `notStep`
  // skips the just-completed step so the race doesn't re-read what we left.
  async function detectNextScreen(opts) {
    opts = opts || {};
    var timeoutMs = opts.timeoutMs || 15000;
    var notStep = opts.notStep;
    var start = Date.now();
    var lastSeen = null;
    while (Date.now() - start < timeoutMs) {
      // Content-identified: the network-acceptance warning shares l2SelectionStep's
      // id, so key off its acknowledge button instead of the step name.
      if (queryVisible(SEL.NETWORK_WARNING_CONTINUE)) return "networkWarning";
      var step = readActiveStep();
      if (step && step !== "loaded" && step !== notStep) {
        lastSeen = step;
        if (step === "assetSelection") return "coin";
        if (step === "l2SelectionStep") return "network";
        if (step === "destinationTagStep") return "destinationTag";
        if (step === "amountEntry") return "amount";
      }
      await D.sleep(150);
    }
    throw new Error('withdraw/no-next-screen: last seen step "' + (lastSeen || "(none)") + '"');
  }

  // React may bind on pointerdown and the container can mount before its cells —
  // humanClick drives the full pointer sequence; retry once if it didn't advance.
  async function clickAndVerifyAdvance(el, label) {
    void label;
    await humanClick(el);
    var start = Date.now();
    while (Date.now() - start < 1500) {
      if (readActiveStep() !== "assetSelection") return;
      await D.sleep(150);
    }
    var again = document.querySelector('[data-testid="' + el.getAttribute("data-testid") + '"]');
    if (again) await humanClick(again);
  }

  async function selectCoin(ticker) {
    var directSelector = SEL.coinDirect(ticker);
    var start = Date.now();
    while (Date.now() - start < 5000) {
      var direct = document.querySelector(directSelector);
      if (direct) { await clickAndVerifyAdvance(direct, "selectCoin(" + ticker + ")"); return; }
      await D.sleep(150);
    }
    var items = Array.prototype.slice.call(document.querySelectorAll(SEL.COIN_LIST));
    for (var i = 0; i < items.length; i++) {
      var testId = items[i].getAttribute("data-testid") || "";
      var t = testId.replace("send-asset-selector-cell-", "").replace("-cell-pressable", "").toUpperCase();
      if (t === ticker.toUpperCase()) { await clickAndVerifyAdvance(items[i], "selectCoin"); return; }
    }
    var available = items.map(function (it) {
      var id = it.getAttribute("data-testid") || "";
      return id.replace("send-asset-selector-cell-", "").replace("-cell-pressable", "");
    }).join(", ");
    throw new Error("withdraw/coin-selection-required: " + ticker + " not in [" + (available || "(none detected)") + "]");
  }

  // Returns null when Coinbase skipped network selection (amount already visible).
  async function selectNetwork(network) {
    var which = await waitForAny([SEL.NETWORK_ITEMS, SEL.CURRENCY_INPUT], 15000);
    if (!which) throw new Error("Neither network list nor amount input appeared");
    if (which === SEL.CURRENCY_INPUT) return null;

    await humanDelay(400); // list finishes rendering fees
    var slug = network.toLowerCase();
    var direct = document.querySelector(SEL.networkTestId(slug));
    if (direct) {
      if (isDisabled(direct)) {
        throw new Error('withdraw/network-unsupported-for-address: "' + network +
          '" is shown as disabled — Coinbase does not support this network for the pasted address');
      }
      await humanClick(direct);
      return slug;
    }
    var all = Array.prototype.slice.call(document.querySelectorAll(SEL.NETWORK_ITEMS_ANY));
    var labels = all.map(function (el) {
      var testid = el.getAttribute("data-testid") || "";
      var name = testid.replace("l2-list-item-", "").replace("-cell-pressable", "");
      return isDisabled(el) ? (name + " (disabled)") : name;
    });
    throw new Error('withdraw/network-unavailable: "' + network + '" not in [' + (labels.join(", ") || "(none detected)") + ']');
  }

  // Network-acceptance warning ("Does your recipient accept <ASSET> on <NETWORK>?").
  // The host already chose this network in the payload, so confirm support and
  // advance. The "don't show again" checkbox is left untouched.
  async function dismissNetworkWarning() {
    var btn = await waitForElement(SEL.NETWORK_WARNING_CONTINUE, 5000);
    await humanClick(btn);
  }

  var RECIPIENT_TYPE_OPTION = {
    "self-custody": SEL.SELF_CUSTODY_OPTION,
    "exchange": SEL.EXCHANGE_OPTION
  };

  // Post-amount recipient-type chooser (some asset/network pairs). No-op when absent.
  async function selectRecipientTypeIfPresent(type) {
    var step = await waitForElement(SEL.STEP_SELECT_RECIPIENT_TYPE, 10000).catch(function () { return null; });
    if (!step || !queryVisible(SEL.STEP_SELECT_RECIPIENT_TYPE)) return;
    var btn = step.querySelector(RECIPIENT_TYPE_OPTION[type]);
    if (!btn) throw new Error("withdraw/recipient-type-option-not-found: " + type);
    btn.click();
  }

  // Travel-rule (FATF) "Who are you sending to?" form. Returns "filled" or
  // "not_required" (never appeared). Throws if it appeared but the host didn't
  // supply travelRule data. (The "I'm sending to myself" checkbox is not used —
  // we fill beneficiary name + country.)
  async function fillTravelRule(data) {
    var which = await waitForAny([SEL.BENEFICIARY_NAME, SEL.SEND_NOW], 10000);
    if (which !== SEL.BENEFICIARY_NAME) return "not_required";
    if (!data || !data.name) throw new Error("withdraw/travel-rule-missing-data");

    var nameInput = await waitForElement(SEL.BENEFICIARY_NAME, 5000);
    nameInput.focus();
    setReactValue(nameInput, data.name);
    await D.sleep(200);

    // Variant A: country-select visible immediately. Variant B: it only appears
    // after clicking Continue.
    var countrySelect = queryVisible(SEL.COUNTRY_SELECT);
    if (!countrySelect) {
      var submit0 = queryVisible(SEL.SUBMIT_BUTTON);
      if (submit0) submit0.click();
      try {
        countrySelect = await waitForElement(SEL.COUNTRY_SELECT, 10000);
      } catch (e) {
        return "filled"; // jurisdiction skipped the country step
      }
    }

    if (data.country) {
      countrySelect.click();
      await D.sleep(400);
      try {
        var opt = await waitForElement(SEL.countryOption(data.country), 5000);
        opt.click();
      } catch (e) { /* option missing — let a downstream error surface it */ }
    }

    var submit = await waitForElement(SEL.SUBMIT_BUTTON, 5000);
    submit.click();
    await D.sleep(500);
    return "filled";
  }

  async function pickDropdownOption(buttonSelector, optionLabel) {
    var container = await waitForElement(buttonSelector, 5000);
    // The testid sits on a wrapper <div>; the real trigger is the listbox
    // <button> inside it. Clicking the wrapper does nothing — resolve the button.
    var button = container.querySelector('button[aria-haspopup="listbox"]')
      || container.querySelector('button, [role="button"]')
      || container;
    await humanClick(button); // cds dropdown ignores a plain .click() — needs pointer events
    await D.sleep(400);
    var deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      for (var i = 0; i < SEL.DROPDOWN_OPTION_SELECTORS.length; i++) {
        var opts = Array.prototype.slice.call(document.querySelectorAll(SEL.DROPDOWN_OPTION_SELECTORS[i]));
        for (var j = 0; j < opts.length; j++) {
          if (getInnerText(opts[j]).trim() === String(optionLabel).trim()) {
            await humanClick(opts[j]);
            await D.sleep(200);
            return;
          }
        }
      }
      await D.sleep(150);
    }
    throw new Error("withdraw/dropdown-option-not-found: " + optionLabel);
  }

  // Transfer-details form (shown on some regulated corridors): purpose +
  // relationship dropdowns. Returns "filled" / "not_required"; throws if it
  // appeared without data.
  async function fillTransferDetails(data) {
    var which = await waitForAny([SEL.TRANSFER_PURPOSE, SEL.SEND_NOW], 10000);
    if (which !== SEL.TRANSFER_PURPOSE) return "not_required";
    if (!data || !data.purpose) {
      throw new Error("withdraw/transfer-details-missing-data: purpose");
    }
    await pickDropdownOption(SEL.TRANSFER_PURPOSE, data.purpose);

    // Relationship is optional: some jurisdictions/variants omit it (or only
    // render it after a purpose is chosen). Fill it only when actually present.
    var rel = await waitForElement(SEL.TRANSFER_RELATIONSHIP, 2000).catch(function () { return null; });
    if (rel) {
      if (!data.relationship) throw new Error("withdraw/transfer-details-missing-data: relationship");
      await pickDropdownOption(SEL.TRANSFER_RELATIONSHIP, data.relationship);
    }

    var submit = await waitForElement(SEL.TRANSFER_SUBMIT, 5000);
    submit.click();
    await D.sleep(500);
    return "filled";
  }

  // ── Currency-aware amount input ──
  var CURRENCY_SYMBOL = '[data-testid="currency-input"] + span[class*="FlexibleCurrencyInput__StyledSymbol"]';
  var CURRENCY_TOGGLE = 'button[aria-label="switch"]';

  function readCurrencySymbol(root) {
    var el = root.querySelector(CURRENCY_SYMBOL);
    return el ? (getInnerText(el) || null) : null;
  }
  function symbolMatchesRequest(symbol, requested, asset) {
    var isAsset = symbol.toUpperCase() === String(asset).toUpperCase();
    return requested === "asset" ? isAsset : !isAsset;
  }

  // Toggle until the input's symbol matches `requested` mode (asset vs local
  // fiat). Throws if the mode is unreachable — prevents typing "10 USDC" worth
  // of value while the field is in a fiat mode.
  async function ensureCurrencyMode(root, requested, asset) {
    for (var attempt = 0; attempt < 3; attempt++) {
      var symbol = readCurrencySymbol(root);
      if (!symbol) throw new Error("Amount-entry step is missing the currency symbol");
      if (symbolMatchesRequest(symbol, requested, asset)) return symbol;
      var toggle = root.querySelector(CURRENCY_TOGGLE);
      if (!toggle) {
        throw new Error('Coinbase only offers "' + symbol + '" for this flow — cannot switch to "' + requested + '" mode');
      }
      toggle.click();
      await D.sleep(400);
    }
    throw new Error('Could not switch Coinbase amount input to "' + requested + '" mode');
  }

  async function enterAmount(amount, coin) {
    var input = await waitForElement(SEL.CURRENCY_INPUT, 15000);
    if (amount === "max") {
      var maxBtn = await waitForElement(SEL.MAX_BUTTON, 5000);
      await humanClick(maxBtn);
    } else {
      var stepEl = input.closest('[data-testid^="step-"][data-testid$="-active"]') || document.body;
      await ensureCurrencyMode(stepEl, amount.currency, coin);
      await typeLikeHuman(input, amount.value);
    }
    await humanDelay(300);
    var preview = queryVisible(SEL.PREVIEW_SEND);
    if (preview) await humanClick(preview);
    await rejectIfAmountInvalid();
  }

  function classifyAmountError(msg) {
    var lower = msg.toLowerCase();
    if (lower.indexOf("add at least") !== -1 || lower.indexOf("insufficient") !== -1 ||
        lower.indexOf("not enough") !== -1 || lower.indexOf("exceeds your balance") !== -1 ||
        lower.indexOf("more than your balance") !== -1) {
      return "withdraw/insufficient-funds";
    }
    if (lower.indexOf("minimum") !== -1) return "withdraw/below-minimum";
    return "withdraw/amount-validation";
  }

  function readAmountValidationError() {
    var errEl = queryVisible(SEL.AMOUNT_ERROR_MESSAGE);
    var raw = errEl ? getInnerText(errEl) : "";
    var msg = raw.replace(/\s*View balance\s*$/i, "").trim();
    var balanceEl = queryVisible(SEL.ASSET_BALANCE);
    var balance = balanceEl ? getInnerText(balanceEl).replace(/\s+/g, " ") : null;
    if (!msg) return "withdraw/amount-validation: amount rejected (no error text surfaced)";
    var code = classifyAmountError(msg);
    return balance ? (code + ": " + msg + " (balance: " + balance + ")") : (code + ": " + msg);
  }

  // Coinbase validates the amount inline; a rejected value keeps the modal on
  // the amount screen. Surface the error promptly; return once it advances.
  async function rejectIfAmountInvalid() {
    var deadline = Date.now() + 2000;
    while (Date.now() < deadline) {
      if (queryVisible(SEL.SEND_NOW) || !queryVisible(SEL.CURRENCY_INPUT)) return;
      var err = queryVisible(SEL.AMOUNT_ERROR_MESSAGE);
      if (err && getInnerText(err)) throw new Error(readAmountValidationError());
      await D.sleep(150);
    }
  }

  function requireNetwork(payload) {
    if (!payload.network) {
      throw new Error("withdraw/network-required: Coinbase asked for a network but none was provided");
    }
    return payload.network;
  }

  // XRP/ATOM/XLM/EOS prompt for a destination tag (memo) between network
  // selection and amount. Fill it (then Continue) when supplied; the orchestrator
  // calls skip otherwise. Continue stays disabled until Coinbase's async tag-format
  // validation settles, so we wait for the enabled button.
  async function fillDestinationTag(tag) {
    var input = await waitForElement(SEL.DESTINATION_TAG_INPUT, 5000);
    var step = await waitForElement(SEL.STEP_DESTINATION_TAG, 1000);
    input.focus();
    setReactValue(input, tag);
    await D.sleep(200); // let Coinbase's async tag-format validation settle
    var continueBtn = await waitForButtonByText("Continue", {
      root: step, requireEnabled: true, timeoutMs: 5000
    });
    await humanClick(continueBtn);
  }

  async function skipDestinationTag() {
    var skipBtn = await waitForElement(SEL.SKIP_DESTINATION_TAG, 5000);
    await humanClick(skipBtn);
  }

  // Walk Coinbase's selection screens (coin / network / destination-tag) until
  // amount entry.
  async function runSelectionPhase(payload) {
    var SELECTION = {
      coin: { run: function () { return selectCoin(payload.asset); }, step: "assetSelection" },
      network: { run: function () { return selectNetwork(requireNetwork(payload)); }, step: "l2SelectionStep" },
      networkWarning: { run: function () { return dismissNetworkWarning(); }, step: "l2SelectionStep" },
      destinationTag: {
        run: function () {
          return payload.destinationTag
            ? fillDestinationTag(payload.destinationTag)
            : skipDestinationTag();
        },
        step: "destinationTagStep"
      }
    };
    var prev;
    var visited = {};
    for (;;) {
      var next = await detectNextScreen({ notStep: prev });
      if (next === "amount") return;
      if (visited[next]) throw new Error("withdraw/selection-phase-stalled: revisited " + next);
      visited[next] = true;
      var handler = SELECTION[next];
      await handler.run();
      prev = handler.step;
    }
  }

  // ── Confirm + 2FA detection + result ──

  function readSendPreview() {
    function text(sel) { var el = queryVisible(sel); return el ? getInnerText(el) : null; }
    return {
      fiatAmount: text(SEL.SEND_PREVIEW_FIAT),
      cryptoAmount: text(SEL.SEND_PREVIEW_CRYPTO),
      recipient: text(SEL.SEND_PREVIEW_RECIPIENT),
      network: text(SEL.SEND_PREVIEW_NETWORK),
      timeEstimate: text(SEL.SEND_PREVIEW_TIME),
      fee: text(SEL.SEND_PREVIEW_FEE)
    };
  }

  // Some flows submit straight from amount/preview into a gate with no separate
  // "Send now" — so also watch the post-send screens and hand off if one appears.
  var POST_SEND_GATES = [
    SEL.IDENTITY_ACCESS_WRAPPER, SEL.OTP_CONTAINER, SEL.OTP_INPUT,
    SEL.TWO_FACTOR_TOTP, SEL.TWO_FACTOR_SMS, SEL.PASSKEY_PROMPT,
    SEL.STEP_RISK_VERIFICATION, SEL.SEND_SUCCESS, SEL.STATUS_COMPLETE_BTN
  ];

  async function confirmAndSend() {
    var which = await waitForAny([SEL.SEND_NOW, SEL.CURRENCY_INPUT].concat(POST_SEND_GATES), 15000);
    if (!which) throw new Error("Neither confirm screen nor amount screen appeared");
    if (which === SEL.CURRENCY_INPUT) throw new Error(readAmountValidationError());
    // Advanced past confirm into a 2FA/risk/success gate — read what we can; the
    // caller's detectAndHandle2fa handles the gate next.
    if (which !== SEL.SEND_NOW) return readSendPreview();

    var details = readSendPreview();
    await humanDelay(500); // let the preview settle / human "review"
    var sendBtn = await waitForElement(SEL.SEND_NOW, 5000);
    await humanClick(sendBtn);
    return details;
  }

  // Read the value rendered next to a label span (label span → sibling value span).
  function readLabeledValue(root, label) {
    var spans = root.querySelectorAll("span");
    for (var i = 0; i < spans.length; i++) {
      if ((spans[i].textContent || "").trim() === label) {
        var sib = spans[i].nextElementSibling;
        var t = (sib && sib.textContent || "").trim();
        return t || null;
      }
    }
    return null;
  }

  function parseRiskCompleteBefore() {
    var step = queryVisible(SEL.STEP_RISK_VERIFICATION);
    return step ? readLabeledValue(step, SEL.RISK_COMPLETE_BEFORE_LABEL) : null;
  }

  function isOtpScreen() {
    return !!(queryVisible(SEL.OTP_INPUT) || queryVisible(SEL.OTP_CONTAINER) ||
              queryVisible(SEL.TWO_FACTOR_TOTP) || queryVisible(SEL.TWO_FACTOR_SMS));
  }

  function activeGate() {
    if (queryVisible(SEL.STEP_RISK_VERIFICATION)) return "id-verification";
    if (isOtpScreen()) return "otp";
    if (queryVisible(SEL.PASSKEY_PROMPT)) return "passkey";
    return null;
  }

  // Prefer a typed-code method (SMS first, then TOTP) the host can relay over a
  // passkey. Returns true if it switched to an OTP method.
  function chooseOtpMethod() {
    var sms = queryVisible(SEL.TWO_FACTOR_SMS);
    if (sms) { sms.click(); return true; }
    var totp = queryVisible(SEL.TWO_FACTOR_TOTP);
    if (totp) { totp.click(); return true; }
    return false;
  }

  function wasTransferCanceled() {
    return !!queryVisible(SEL.STEP_USER_CANCELLATION);
  }

  // "past 2FA / send accepted": success content, Complete button, or the send
  // modal overlay gone — but NOT while a gate is up or after a cancellation.
  function past2fa() {
    if (wasTransferCanceled()) return false;
    if (activeGate()) return false;
    if (queryVisible(SEL.SEND_SUCCESS)) return true;
    if (queryVisible(SEL.STATUS_COMPLETE_BTN)) return true;
    var overlay = document.querySelector(SEL.MODAL_OVERLAY);
    if (!overlay) return true;
    if (!queryVisible(SEL.MODAL_OVERLAY)) return true;
    return false;
  }

  // Race success vs the various 2FA UIs (30s). Returns a TwoFaOutcome:
  // { kind: "none" | "otp" | "passkey" | "processing" | "canceled" |
  //   "id-verification" (+ completeBefore) }.
  async function detectAndHandle2fa() {
    var ANY_UI = [
      SEL.SEND_SUCCESS, SEL.STATUS_COMPLETE_BTN, SEL.IDENTITY_ACCESS_WRAPPER,
      SEL.STATUS_LOADING, SEL.TWO_FACTOR_TOTP, SEL.TWO_FACTOR_SMS,
      SEL.PASSKEY_PROMPT, SEL.STEP_RISK_VERIFICATION, SEL.STEP_USER_CANCELLATION
    ];
    var which = await waitForAny(ANY_UI, 30000);
    if (wasTransferCanceled()) return { kind: "canceled" };
    if (!which) {
      if (past2fa()) return { kind: "none" };
      throw new Error("withdraw/unknown-failure: neither success nor 2FA UI appeared");
    }
    if (past2fa()) return { kind: "none" };
    if (which === SEL.STEP_RISK_VERIFICATION || queryVisible(SEL.STEP_RISK_VERIFICATION)) {
      return { kind: "id-verification", completeBefore: parseRiskCompleteBefore() };
    }
    await D.sleep(1500); // let the modal settle before inspecting buttons
    if (past2fa()) return { kind: "none" };
    if (queryVisible(SEL.STEP_RISK_VERIFICATION)) {
      return { kind: "id-verification", completeBefore: parseRiskCompleteBefore() };
    }
    if (chooseOtpMethod()) return { kind: "otp" };
    if (queryVisible(SEL.PASSKEY_PROMPT)) return { kind: "passkey" };
    if (isOtpScreen()) return { kind: "otp" };
    return { kind: "processing" };
  }

  // ── Commit-send network interceptor (AUTH-3444) ──
  //
  // Android's shouldInterceptRequest exposes the request but not the response
  // body, so — like the browser extension — we capture the authoritative send
  // outcome in-page by patching fetch + XHR and reading Coinbase's
  // `useCommitSendMutation` GraphQL response. The captured body lives on `window`
  // (survives start→continue on the un-navigated modal page); readCommittedSend()
  // pulls referenceId / sendUuid from it, with the success headline as fallback.

  var COMMIT_SEND_OP = "useCommitSendMutation";

  // Strict host check — endsWith(".coinbase.com") so `notcoinbase.com.evil.tld`
  // can't slip through (mirrors the extension's isCoinbaseHost).
  function isCoinbaseHost(h) {
    if (!h) return false;
    if (h === "coinbase.com") return true;
    var suffix = ".coinbase.com";
    return h.length > suffix.length && h.lastIndexOf(suffix) === h.length - suffix.length;
  }

  function isGraphQLEndpoint(url) {
    try {
      var u = new URL(url, window.location.origin);
      return isCoinbaseHost(u.hostname) && u.pathname.indexOf("/graphql") !== -1;
    } catch (e) {
      return false;
    }
  }

  function operationNameFor(url, body) {
    try {
      var u = new URL(url, window.location.origin);
      var q = u.searchParams.get("operationName");
      if (q) return q;
    } catch (e) {}
    if (body) {
      try { return JSON.parse(body).operationName || null; } catch (e) {}
    }
    return null;
  }

  // Store the commit-send GraphQL body when we see it — matched by op name, or by
  // the response carrying `data.commitSend` (robust to persisted-query GETs where
  // the op name isn't on the request). Never throws.
  function maybeCaptureCommit(url, bodyStr, responseText) {
    if (!isGraphQLEndpoint(url)) return;
    try {
      var body = JSON.parse(responseText);
      var op = operationNameFor(url, bodyStr);
      var hasCommit = !!(body && body.data && body.data.commitSend);
      if (op === COMMIT_SEND_OP || hasCommit) window.__zhCommitSend = body;
    } catch (e) {}
  }

  // Patch fetch + XHR once (guarded on window so re-injection is a no-op).
  function installCommitInterceptor() {
    if (window.__zhCommitInterceptorInstalled) return;
    window.__zhCommitInterceptorInstalled = true;

    var origFetch = window.fetch;
    if (typeof origFetch === "function") {
      window.fetch = function (input, init) {
        var url = typeof input === "string" ? input : (input && input.url) || "";
        var bodyStr = init && typeof init.body === "string" ? init.body : null;
        var p = origFetch.apply(this, arguments);
        try {
          return p.then(function (resp) {
            try {
              if (isGraphQLEndpoint(url)) {
                resp.clone().text().then(function (text) {
                  maybeCaptureCommit(url, bodyStr, text);
                }).catch(function () {});
              }
            } catch (e) {}
            return resp;
          });
        } catch (e) {
          return p;
        }
      };
    }

    var XHR = XMLHttpRequest.prototype;
    var origOpen = XHR.open;
    var origSend = XHR.send;
    XHR.open = function (method, url) {
      this.__zhUrl = url;
      return origOpen.apply(this, arguments);
    };
    XHR.send = function (body) {
      var self = this;
      var bodyStr = typeof body === "string" ? body : null;
      try {
        this.addEventListener("load", function () {
          try { maybeCaptureCommit(self.__zhUrl || "", bodyStr, self.responseText); } catch (e) {}
        });
      } catch (e) {}
      return origSend.apply(this, arguments);
    };
  }

  // Evict a prior send's cached commit response — the modal page is reused, so a
  // stale body would otherwise surface the previous send's ids/status. Called at
  // the start of a new send; if this send's commit isn't captured, readCommittedSend
  // returns null and waitForResult falls back to the success headline.
  function forgetCommittedSend() {
    window.__zhCommitSend = null;
  }

  // Read the authoritative outcome from the intercepted useCommitSendMutation
  // response (the source of truth at submit time). Returns null when it wasn't
  // captured, so the caller falls back to the success headline. completeBefore is
  // always null here — a hold is decided server-side, after this flow.
  function readCommittedSend() {
    var body = window.__zhCommitSend;
    var commitSend = body && body.data && body.data.commitSend;
    if (!commitSend) return null;
    var send = commitSend.send;
    var status = (commitSend.__typename && commitSend.__typename !== "SendsCommitSendSuccess")
      ? "failed"
      : ((send && send.status) || "success");
    return {
      status: status,
      completeBefore: null,
      referenceId: (send && send.referenceId) || null,
      sendUuid: (send && send.uuid) || null
    };
  }

  // Determine the outcome once the success screen renders. Prefer the intercepted
  // commit response (authoritative status + the send's ids); fall back to
  // classifying the success headline only when that response isn't captured.
  async function waitForResult() {
    try {
      await waitForElement(SEL.SEND_SUCCESS + ", " + SEL.STATUS_COMPLETE_BTN, 60000);
    } catch (e) {
      return { status: "timeout", completeBefore: null, referenceId: null, sendUuid: null };
    }
    var committed = readCommittedSend();
    if (committed) return committed;
    var headline = queryVisible(SEL.SUCCESS_HEADLINE);
    var t = headline ? getInnerText(headline).toLowerCase() : "";
    var failureKeywords = ["fail", "error", "cancel", "falh", "erro"];
    var failed = failureKeywords.some(function (kw) { return t.indexOf(kw) !== -1; });
    return {
      status: failed ? "failed" : (t || "success"),
      completeBefore: null, referenceId: null, sendUuid: null
    };
  }

  async function finalizeSubmitted(details) {
    var r = await waitForResult();
    return {
      state: "submitted",
      result: {
        status: r.status, completeBefore: r.completeBefore,
        referenceId: r.referenceId, sendUuid: r.sendUuid, details: details
      }
    };
  }

  function toState(outcome, details) {
    switch (outcome.kind) {
      case "canceled": return { state: "rejected", reason: "transfer_canceled" };
      case "otp": return { state: "awaiting-input", kind: "otp", details: details };
      // Passkey is NOT supported: WKWebView can't complete a third-party WebAuthn
      // ceremony and Coinbase offered no code alternative. Reject (terminal) so the
      // host can tell the user to enable SMS/authenticator 2FA.
      case "passkey": return { state: "rejected", reason: "passkey_unsupported" };
      case "id-verification":
        return { state: "awaiting-user-action", kind: "id-verification", details: details, completeBefore: outcome.completeBefore };
      case "processing": return { state: "processing", details: details };
      default: throw new Error("withdraw/unreachable-2fa-kind: " + outcome.kind);
    }
  }

  // ── OTP / poll (continue path) ──

  // Persisted across start→continue on window (the modal page isn't navigated,
  // so it survives; degrades to empty details if a reload ever wipes it).
  function moduleState() {
    if (!window.__zhWithdrawState) window.__zhWithdrawState = { details: null };
    return window.__zhWithdrawState;
  }

  function dispatchPaste(input, text) {
    try {
      var dt = new DataTransfer();
      dt.setData("text/plain", text);
      input.dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true }));
    } catch (e) {}
  }

  // Newer Coinbase builds render one input per digit; older builds use a single
  // field. Distribute one digit per box when there are several, else set the
  // whole value (+ paste for variants that only split on paste).
  function fillOtpCode(firstInput, code) {
    var container = firstInput.closest(SEL.OTP_CONTAINER) || queryVisible(SEL.OTP_CONTAINER);
    var boxes = container ? Array.prototype.slice.call(container.querySelectorAll("input")) : [firstInput];
    if (boxes.length <= 1) {
      firstInput.focus();
      setReactValue(firstInput, code);
      dispatchPaste(firstInput, code);
      return;
    }
    var digits = String(code).split("");
    for (var i = 0; i < boxes.length; i++) {
      boxes[i].focus();
      setReactValue(boxes[i], digits[i] || "");
    }
  }

  // Type the code. true = accepted (past2fa flipped or the code screen advanced);
  // false = Coinbase cleared the field (bad code). Throws on timeout (a hang).
  async function enterOtp(code) {
    await waitForAny([SEL.OTP_INPUT, SEL.OTP_CONTAINER, SEL.IDENTITY_ACCESS_WRAPPER], 15000);
    if (past2fa()) return true;
    var input = await waitForElement(SEL.OTP_INPUT, 15000);
    fillOtpCode(input, code);
    await D.sleep(300);
    var start = Date.now();
    while (Date.now() - start < 15000) {
      if (past2fa()) return true;
      if (Date.now() - start > 1500) { // give React ~1.5s before reading the field
        var current = document.querySelector(SEL.OTP_INPUT);
        if (current && current.value === "") return false; // rejected: field cleared
        if (!queryVisible(SEL.OTP_INPUT) && !queryVisible(SEL.OTP_CONTAINER)) return true; // advanced
      }
      await D.sleep(500);
    }
    throw new Error("withdraw/otp-timeout: Coinbase didn't accept or reject the code");
  }

  // Short poll for continue({kind:"poll"}).
  async function pollFor2faResolution() {
    var deadline = Date.now() + 10000;
    while (Date.now() < deadline) {
      if (wasTransferCanceled()) return "canceled";
      if (past2fa()) return "submitted";
      if (queryVisible(SEL.STEP_RISK_VERIFICATION)) return "id-verification";
      if (chooseOtpMethod()) return "otp";
      if (isOtpScreen()) return "otp";
      if (queryVisible(SEL.PASSKEY_PROMPT)) return "passkey";
      await D.sleep(500);
    }
    return "processing";
  }

  // Click "Cancel transfer" on the risk step. true if found+clicked, false if the
  // step isn't up (e.g. already verified/cancelled in the UI).
  async function clickCancelTransfer() {
    var step = queryVisible(SEL.STEP_RISK_VERIFICATION);
    if (!step) return false;
    var btn = await waitForButtonByText(SEL.RISK_CANCEL_TRANSFER_LABEL, { root: step, timeoutMs: 500 })
      .catch(function () { return null; });
    if (!btn) return false;
    await humanClick(btn);
    return true;
  }

  // ─── Entry points ───────────────────────────────────────────────────

  function emptyDetails() {
    return {
      fiatAmount: null, cryptoAmount: null, recipient: null,
      network: null, timeEstimate: null, fee: null
    };
  }

  window.__zhWithdraw = {
    // Drive Send → forms → preview → "Send now", then detect & return the 2FA state.
    start: async function (params) {
      try {
        // Capture this send's commit response; drop any prior send's first.
        installCommitInterceptor();
        forgetCommittedSend();
        await openSendModal();
        await enterRecipient(params.address);
        await runSelectionPhase(params);
        await enterAmount(params.amount, params.asset);
        await selectRecipientTypeIfPresent(params.recipientType || "self-custody");
        await fillTravelRule(params.travelRule);
        await fillTransferDetails(params.transferDetails);
        var details = await confirmAndSend();
        moduleState().details = details; // persist for continue()
        var outcome = await detectAndHandle2fa();
        if (outcome.kind === "none") return await finalizeSubmitted(details);
        return toState(outcome, details);
      } catch (e) {
        // A prior transfer pending verification blocks this send — terminal, but
        // surface its details (not a generic error) so the host can tell the user
        // what to resolve at coinbase.com.
        if (e && e.zhPendingTransfer) {
          return { state: "rejected", reason: "pending_transfer", pendingTransfer: e.zhPendingTransfer };
        }
        throw e;
      }
    },
    // OTP/poll follow-up on the SAME live session.
    continue: async function (payload) {
      var details = moduleState().details || emptyDetails();
      if (!payload || typeof payload !== "object" || !("kind" in payload)) {
        throw new Error("withdraw/invalid-payload: kind");
      }

      if (payload.kind === "otp") {
        if (!payload.code || !/^\d{6}$/.test(payload.code)) {
          throw new Error("withdraw/invalid-payload: code (expected 6 digits)");
        }
        var accepted = await enterOtp(payload.code);
        if (!accepted) return { state: "rejected", reason: "otp_rejected" }; // retriable
        var outcome = await detectAndHandle2fa();
        if (outcome.kind === "none") return await finalizeSubmitted(details);
        return toState(outcome, details);
      }

      if (payload.kind === "poll") {
        if (wasTransferCanceled()) return { state: "rejected", reason: "transfer_canceled" };
        if (past2fa()) return await finalizeSubmitted(details);
        var next = await pollFor2faResolution();
        if (next === "canceled") return { state: "rejected", reason: "transfer_canceled" };
        if (next === "submitted") return await finalizeSubmitted(details);
        if (next === "processing") return { state: "processing", details: details };
        if (next === "otp") return { state: "awaiting-input", kind: "otp", details: details };
        if (next === "passkey") return { state: "rejected", reason: "passkey_unsupported" }; // passkey not supported (see toState)
        return { state: "awaiting-user-action", kind: "id-verification", details: details, completeBefore: parseRiskCompleteBefore() };
      }

      throw new Error("withdraw/invalid-payload: unknown kind");
    },
    // Click Coinbase's "Cancel transfer"; reports whether it was found and clicked.
    cancel: async function () {
      return { cancelled: await clickCancelTransfer() };
    }
  };
})();
