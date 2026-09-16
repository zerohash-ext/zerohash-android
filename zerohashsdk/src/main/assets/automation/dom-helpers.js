// Generic, platform-agnostic DOM/timing helpers shared by every SDK automation
// (Coinbase deposit, future withdrawal, etc.). No platform selectors and no
// per-run state: callers pass an absolute deadline (ms since epoch) so the
// poll helpers stay reusable across automations with different timeouts.
//
// Loaded by prepending this file inside each automation's wrapped IIFE (see
// Coinbase.swift), so `window.__zhDom` exists before the automation body runs.
window.__zhDom = (function () {
  var TESTID_CAP = 40;

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

  function setReactValue(input, value) {
    var ctor = input.tagName === "TEXTAREA"
      ? window.HTMLTextAreaElement
      : window.HTMLInputElement;
    var desc = ctor && ctor.prototype
      ? Object.getOwnPropertyDescriptor(ctor.prototype, "value")
      : null;
    if (desc && desc.set) { desc.set.call(input, value); } else { input.value = value; }
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }

  // Collapses id-shaped segments to ":id". Digits required, so camelCase names
  // are not mistaken for ids.
  function normalizeTestid(id) {
    var parts = String(id).replace(/[\[\]]/g, "").replace(/-cell-pressable$/, "").split("-");
    for (var i = 0; i < parts.length; i++) {
      var s = parts[i];
      if (/^0x[0-9a-fA-F]{6,}$/.test(s) || /^[0-9a-fA-F]{16,}$/.test(s) || /^\d{6,}$/.test(s) ||
          (s.length >= 26 && /\d/.test(s) && /^[A-Za-z0-9]+$/.test(s))) {
        parts[i] = ":id";
      }
    }
    return parts.join("-");
  }

  function collectTestids() {
    var nodes = document.querySelectorAll("[data-testid]");
    var out = [];
    for (var i = 0; i < nodes.length; i++) {
      var v = nodes[i].getAttribute("data-testid");
      if (v) out.push(v);
    }
    return out;
  }

  // ids -> { total, unique, list }. `priorityRe` entries sort first, ahead of the cap.
  function summarizeTestids(ids, priorityRe, cap) {
    cap = cap || TESTID_CAP;
    var seen = {};
    var priority = [];
    var rest = [];
    for (var i = 0; i < ids.length; i++) {
      var n = normalizeTestid(ids[i]);
      if (!n || Object.prototype.hasOwnProperty.call(seen, n)) continue;
      seen[n] = true;
      ((priorityRe && priorityRe.test(n)) ? priority : rest).push(n);
    }
    var unique = priority.concat(rest);
    var list = unique.slice(0, cap).join(",");
    if (unique.length > cap) list += ",+" + (unique.length - cap);
    return { total: ids.length, unique: unique.length, list: list };
  }

  // Which data-testids are mounted, for reporting an unrecognised screen.
  function testidCensus(priorityRe, cap) {
    return summarizeTestids(collectTestids(), priorityRe, cap);
  }

  return {
    sleep: sleep,
    $: $,
    waitUntil: waitUntil,
    waitFor: waitFor,
    realisticClick: realisticClick,
    findButtonByText: findButtonByText,
    clickableAncestor: clickableAncestor,
    setReactValue: setReactValue,
    normalizeTestid: normalizeTestid,
    summarizeTestids: summarizeTestids,
    collectTestids: collectTestids,
    testidCensus: testidCensus
  };
})();
