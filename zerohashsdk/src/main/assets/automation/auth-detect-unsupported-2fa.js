// ─── Coinbase: unsupported-2FA detection ─────────────────────────────
//
// Injected against login.coinbase.com and polled by the login modal's
// auto-close. Returns `true` when the account can ONLY complete 2FA with a
// passkey (WebAuthn) — which can't be finished inside an embedded WebView —
// so the login flow resolves the `passkey-only` outcome. Returns `false`
// whenever any non-passkey factor (password, SMS, TOTP, …) is reachable.
(function () {
  function has(sel) {
    return document.querySelector(sel) !== null;
  }

  var passkeyScreen =
    has('[data-testid="passkey-verify-button"]') ||
    has('[data-testid^="identity-multi-content-layout-content-wrapper-passkey-auth"]');
  if (!passkeyScreen) return false;

  // Any path that lets the user authenticate without a passkey.
  if (
    has('input[type="password"]') ||
    has('[data-testid="password-input"]') ||
    has('[data-testid="two-factor-button-PASSWORD"]')
  ) {
    return false;
  }

  // Coinbase's newer login design shows only the primary factor's screen and
  // hides alternate 2FA factors (SMS/TOTP) behind a "try another way" tray —
  // they are NOT in the DOM until the tray is expanded. If that tray exists
  // but is still closed, a supported factor may yet be available, so we must
  // not call this unsupported. auth-choose-2fa-method.js opens the tray; once
  // it is expanded (the `tray`/`tray-overlay` nodes render) the check below
  // sees any real alternate. Genuinely passkey-only accounts have no such
  // tray (or an expanded tray with no non-passkey factor).
  var trayClosed =
    has('[data-testid="try-another-way-button"]') &&
    !has('[data-testid="tray"]') &&
    !has('[data-testid="tray-overlay"]');
  if (trayClosed) return false;

  // Any non-password 2FA factor offered as an alternate (TOTP, SMS, …),
  // inline (old design) or inside the expanded tray (new design).
  var others = document.querySelectorAll('[data-testid^="two-factor-button-"]');
  for (var i = 0; i < others.length; i++) {
    if (others[i].getAttribute("data-testid") !== "two-factor-button-PASSWORD") {
      return false;
    }
  }

  return true;
})();
