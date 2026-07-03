// ─── Coinbase: 2FA method chooser ────────────────────────────────────
//
// documentStart script for the login modal. Auto-advances the user to a 2FA
// factor we CAN complete inside an embedded WebView, preferring Password,
// then an OTP factor (SMS/TOTP). Passkeys can't complete in a WebView, so we
// never pick them.
//
// Two Coinbase login designs are handled:
//   • Old: a method-selection screen renders every factor inline as a
//     `two-factor-button-*` — we click the supported one directly.
//   • New: only the primary factor's screen renders; alternates live behind a
//     "try another way" tray. When the primary is an (unsupported) passkey
//     screen, we open the tray so its factors render, then click a supported
//     one — routing the user to the SMS/TOTP entry the host already collects.
//     When the primary is already a supported screen (e.g. SMS OTP entry) we
//     leave it alone and never pop the tray.
(function () {
  if (location.hostname !== "login.coinbase.com") return;

  // Single-injection guard. This script is injected on BOTH onPageStarted and
  // onPageFinished (documentStart-equivalent + backstop). Without a shared flag
  // each injection installs its own MutationObserver, so two instances can each
  // fire finish()→click() on the chosen factor (double-click), and every
  // navigation leaks one observer until it self-disconnects. Coordinate through
  // a window-level singleton: only the first injection into a given document
  // installs the observer and performs the click. A real navigation gets a
  // fresh `window` and re-arms naturally.
  if (window.__zhChoose2faInstalled) return;
  window.__zhChoose2faInstalled = true;

  var done = false;       // a supported factor was chosen — stop.
  var trayOpened = false; // we've clicked the tray open at least once.
  var observer = null;

  function q(sel) {
    return document.querySelector(sel);
  }

  function onPasskeyScreen() {
    return (
      q('[data-testid="passkey-verify-button"]') !== null ||
      q('[data-testid^="identity-multi-content-layout-content-wrapper-passkey-auth"]') !== null
    );
  }

  // A supported (non-passkey) 2FA factor button, preferring Password, then
  // SMS, then any other non-passkey factor (e.g. TOTP). Skips the per-cell
  // "-cell-pressable" duplicates so we click the real button.
  function pickSupportedFactor() {
    var pw = q('[data-testid="two-factor-button-PASSWORD"]');
    if (pw) return pw;
    var sms = q('[data-testid="two-factor-button-SMS"]');
    if (sms) return sms;
    var all = document.querySelectorAll('[data-testid^="two-factor-button-"]');
    for (var i = 0; i < all.length; i++) {
      var id = all[i].getAttribute("data-testid") || "";
      if (id.indexOf("PASSKEY") !== -1) continue;
      if (id.indexOf("-cell-pressable") !== -1) continue;
      return all[i];
    }
    return null;
  }

  function finish(el) {
    done = true;
    if (observer) observer.disconnect();
    el.click();
  }

  function step() {
    if (done) return;

    // 1) A supported factor is directly reachable — take it.
    var factor = pickSupportedFactor();
    if (factor) {
      finish(factor);
      return;
    }

    // 2) No supported factor visible. Only when stranded on a passkey screen
    //    do we open the "try another way" tray once, so its alternate factors
    //    render; this observer then fires again and step 1 picks one. If the
    //    account is genuinely passkey-only the tray exposes nothing supported
    //    and auth-detect-unsupported-2fa.js resolves `passkey-only`.
    if (!trayOpened && onPasskeyScreen()) {
      var trigger = q('[data-testid="try-another-way-button"]');
      var trayAlreadyOpen =
        q('[data-testid="tray"]') !== null || q('[data-testid="tray-overlay"]') !== null;
      if (trigger && !trayAlreadyOpen) {
        trayOpened = true;
        trigger.click();
      }
    }
  }

  function start() {
    step();
    if (done) return;
    observer = new MutationObserver(step);
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, { once: true });
  } else {
    start();
  }
})();
