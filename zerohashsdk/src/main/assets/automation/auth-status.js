// ─── Coinbase: auth.status detection ─────────────────────────────────
//
// Injected against coinbase.com/home to decide {loggedIn}.
//
// Asymmetry that makes this fast AND correct: a logged-OUT user is always
// redirected to login.coinbase.com (the native runner answers false on
// that host before this script even matters). So the negative needs a
// positive proof — a redirect or a signed-out CTA — and ABSENT that proof,
// sitting on the authenticated home means logged IN. We therefore don't
// wait for the profile avatar to paint (slow on a cold load, which is what
// made the status check feel sluggish); we conclude logged-in once the
// redirect window has passed without a redirect or a sign-in CTA.
//
// Resolution order each tick:
//   host is login.coinbase.com         => logged out   (fast)
//   avatar / glyph present             => logged in     (fast, warm loads)
//   signed-out CTA present on www (A)  => logged out     (after grace)
// and on timeout, the host-aware default:
//   still on the dashboard host        => logged in
//   anything else                      => logged out
(function () {
  // The redirect-wait window. A logged-out redirect to login was observed
  // ~1.9s after the home load; this leaves margin over that before we
  // conclude logged-in. Tunable: raise it if a logged-out user ever slips
  // through as logged-in (slow redirect), lower it to make the logged-in
  // cold path snappier.
  const DETECTION_TIMEOUT_MS = 4000;
  const POLL_INTERVAL_MS = 300;
  // Don't honour the signed-out CTA until the page has had a moment to
  // hydrate; an authed home can briefly render without its avatar.
  const LOGOUT_GRACE_MS = 1200;

  function hostname() {
    try { return new URL(location.href).hostname; } catch (e) { return ""; }
  }

  function isOnLoginPage() {
    return hostname() === "login.coinbase.com";
  }

  // The authenticated app surface. If we're here and were NOT redirected to
  // login, we're signed in.
  function isOnDashboardHost() {
    const h = hostname();
    return h === "www.coinbase.com" || h === "coinbase.com";
  }

  function hasLoggedInMarkers() {
    return (
      !!document.querySelector('[data-testid="ProfileDropdownAvatar-wrapper"]') ||
      !!document.querySelector('[data-testid="icon-base-glyph"]')
    );
  }

  // A sign-in affordance that only the signed-out surface renders. An
  // authenticated header carries the avatar dropdown instead, so these are
  // safe negatives. data-testid candidates first; fall back to a header/nav
  // anchor pointing at the login host (robust to id churn).
  function hasSignedOutCta() {
    if (
      document.querySelector('[data-testid="header-signin-link"]') ||
      document.querySelector('[data-testid="header-getstarted-button"]')
    ) {
      return true;
    }
    const chrome = document.querySelector("header, nav");
    return !!(chrome && chrome.querySelector('a[href*="login.coinbase.com"]'));
  }

  function detectOnce(elapsedMs) {
    if (isOnLoginPage()) return { loggedIn: false };
    if (hasLoggedInMarkers()) return { loggedIn: true };
    if (elapsedMs >= LOGOUT_GRACE_MS && hasSignedOutCta()) return { loggedIn: false };
    return null;
  }

  return new Promise((resolve) => {
    const start = Date.now();
    const tick = () => {
      const elapsed = Date.now() - start;
      const r = detectOnce(elapsed);
      if (r) { resolve(r); return; }
      if (elapsed >= DETECTION_TIMEOUT_MS) {
        // No redirect to login and no signed-out CTA within the window:
        // we're sitting on the authenticated home (the avatar just hasn't
        // painted yet on this cold load). Treat the dashboard host as
        // logged in; anything else as logged out.
        resolve({ loggedIn: isOnDashboardHost() });
        return;
      }
      setTimeout(tick, POLL_INTERVAL_MS);
    };
    tick();
  });
})();
