// Generic, platform-agnostic DOM/timing helpers shared by every SDK automation
// (Coinbase deposit, future withdrawal, etc.). No platform selectors and no
// per-run state: callers pass an absolute deadline (ms since epoch) so the
// poll helpers stay reusable across automations with different timeouts.
//
// Loaded by prepending this file inside each automation's wrapped IIFE (see
// Coinbase.swift), so `window.__zhDom` exists before the automation body runs.
window.__zhDom = (function () {
  function sleep(ms) {
    return new Promise(function (r) { setTimeout(r, ms); });
  }

  function $(sel) {
    return document.querySelector(sel);
  }

  // Polls `find()` until it returns truthy or the bounded deadline passes.
  // `deadlineMs` is an absolute Date.now()-style timestamp; the effective
  // window is min(timeoutMs, remaining-until-deadline). Resolves null on
  // timeout (never rejects) so callers can branch without try/catch.
  function waitUntil(find, timeoutMs, deadlineMs) {
    timeoutMs = timeoutMs || 8000;
    var remaining = Math.max(0, (deadlineMs || (Date.now() + timeoutMs)) - Date.now());
    var end = Date.now() + Math.min(timeoutMs, remaining);
    return new Promise(function (resolve) {
      (function poll() {
        var v = find();
        if (v) return resolve(v);
        if (Date.now() >= end) return resolve(null);
        setTimeout(poll, 150);
      })();
    });
  }

  // Like waitUntil but for a CSS selector; rejects with element_not_found:<sel>
  // on timeout (preserves the original get-deposit-address.js contract).
  function waitFor(sel, timeoutMs, deadlineMs) {
    timeoutMs = timeoutMs || 5000;
    var remaining = Math.max(0, (deadlineMs || (Date.now() + timeoutMs)) - Date.now());
    var end = Date.now() + Math.min(timeoutMs, remaining);
    return new Promise(function (resolve, reject) {
      (function poll() {
        var el = $(sel);
        if (el) return resolve(el);
        if (Date.now() >= end) return reject(new Error("element_not_found:" + sel));
        setTimeout(poll, 150);
      })();
    });
  }

  // cds-Interactable buttons sometimes ignore .click(); fire pointer events too.
  function realisticClick(el) {
    try {
      el.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true }));
      el.dispatchEvent(new PointerEvent("pointerup", { bubbles: true }));
    } catch (e) {}
    try { el.click(); } catch (e) {}
  }

  function findButtonByText(text) {
    var btns = document.querySelectorAll("button, [role='button'], a");
    for (var i = 0; i < btns.length; i++) {
      if ((btns[i].textContent || "").trim().toLowerCase() === text.toLowerCase()) {
        return btns[i];
      }
    }
    return null;
  }

  // Walks up from `el` to the nearest clickable ancestor (button/link or
  // role="button"), bounded so we never escape the row. Generated-class rows
  // with no testid mean the clickable target is an ancestor of the icon anchor.
  function clickableAncestor(el) {
    var node = el;
    var depth = 0;
    while (node && depth < 8) {
      var role = node.getAttribute ? node.getAttribute("role") : null;
      if (node.tagName === "BUTTON" || node.tagName === "A" || role === "button") {
        return node;
      }
      node = node.parentElement;
      depth = depth + 1;
    }
    return el; // fall back to the element itself; clicks still bubble
  }

  return {
    sleep: sleep,
    $: $,
    waitUntil: waitUntil,
    waitFor: waitFor,
    realisticClick: realisticClick,
    findButtonByText: findButtonByText,
    clickableAncestor: clickableAncestor
  };
})();
