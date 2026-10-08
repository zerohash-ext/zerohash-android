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
  const PROFILE_URL = "https://www.coinbase.com/graphql/query?operationName=userQuery";
  const PROFILE_QUERY =
    "query userQuery { viewer { userProperties { uuid personalDetails { legalName { firstName lastName } } } } }";
  const PROFILE_REQUEST = {
    method: "POST",
    credentials: "include",
    cache: "no-store",
    headers: {
      accept: "application/json",
      "content-type": "application/json",
      "x-apollo-operation-name": "userQuery"
    },
    body: JSON.stringify({ operationName: "userQuery", query: PROFILE_QUERY, variables: {} })
  };
  const PROFILE_MAX_ATTEMPTS = 3;
  const PROFILE_ATTEMPT_TIMEOUT_MS = 4000;
  const PROFILE_BACKOFF_BEFORE_RETRY_MS = [500, 1000];
  const PROFILE_DEFAULT_BUDGET_MS = 13500;
  const PROFILE_MIN_ATTEMPT_MS = 1000;
  const FINAL_PROFILE_OUTCOMES = ["ok", "missing_field"];
  const ERR_PROFILE_INDETERMINATE = "PROFILE_INDETERMINATE";
  const ERR_PROFILE_INCOMPLETE = "PROFILE_INCOMPLETE";
  const sharedDom = window.__zhDom;

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

  function emitTelemetry(row) {
    try {
      const telemetry = window.__zhTelemetry;
      if (!telemetry) {
        return;
      }

      telemetry.emit(row);
    } catch (ignoredTelemetryFailure) {
      return;
    }
  }

  function isJsonObject(body) {
    return body !== null && typeof body === "object";
  }

  function hasGraphQLErrors(body) {
    if (!isJsonObject(body)) {
      return false;
    }

    return Array.isArray(body.errors) && body.errors.length > 0;
  }

  function userPropertiesOf(body) {
    if (!isJsonObject(body) || !body.data || !body.data.viewer) {
      return null;
    }

    const userProperties = body.data.viewer.userProperties;
    if (!isJsonObject(userProperties)) {
      return null;
    }

    return userProperties;
  }

  function legalNameOf(userProperties) {
    const personalDetails = userProperties.personalDetails;
    if (!personalDetails || !personalDetails.legalName) {
      return {};
    }

    return personalDetails.legalName;
  }

  function failedAttempt(outcome) {
    return { outcome, profile: null };
  }

  function outcomeWhenUnreadable(body, outcomeWithoutGraphQLErrors) {
    if (hasGraphQLErrors(body)) {
      return "graphql_error";
    }

    return outcomeWithoutGraphQLErrors;
  }

  function classifyProfileResponse(httpStatus, body) {
    if (httpStatus < 200 || httpStatus > 299) {
      return failedAttempt("http_error");
    }

    const userProperties = userPropertiesOf(body);
    if (userProperties === null) {
      return failedAttempt(outcomeWhenUnreadable(body, "invalid_response"));
    }

    const legalName = legalNameOf(userProperties);
    const profile = {
      userId: sharedDom.trimmedText(userProperties.uuid),
      firstName: sharedDom.trimmedText(legalName.firstName),
      lastName: sharedDom.trimmedText(legalName.lastName)
    };
    if (profile.userId === null || profile.firstName === null || profile.lastName === null) {
      return failedAttempt(outcomeWhenUnreadable(body, "missing_field"));
    }

    return { outcome: "ok", profile };
  }

  function outcomeOfRequestFailure(requestFailure) {
    if (requestFailure && requestFailure.name === "AbortError") {
      return "timeout";
    }

    return "http_error";
  }

  async function profileAttempt(timeoutMs) {
    try {
      const response = await sharedDom.fetchTextWithTimeout(PROFILE_URL, PROFILE_REQUEST, timeoutMs);
      const verdict = classifyProfileResponse(response.status, sharedDom.parseJsonOrNull(response.text));

      return { outcome: verdict.outcome, profile: verdict.profile, httpStatus: response.status };
    } catch (requestFailure) {
      return { outcome: outcomeOfRequestFailure(requestFailure), profile: null, httpStatus: null };
    }
  }

  function profileErrorFor(outcome) {
    if (outcome === "ok") {
      return null;
    }

    if (outcome === "missing_field") {
      return ERR_PROFILE_INCOMPLETE;
    }

    return ERR_PROFILE_INDETERMINATE;
  }

  function profileDeadline() {
    if (typeof params === "undefined" || !params || typeof params.profileDeadlineMs !== "number") {
      return Date.now() + PROFILE_DEFAULT_BUDGET_MS;
    }

    return params.profileDeadlineMs;
  }

  function hasTimeForAttempt(deadline, waitBeforeMs) {
    return deadline - Date.now() - waitBeforeMs >= PROFILE_MIN_ATTEMPT_MS;
  }

  async function readProfile() {
    const startedAt = Date.now();
    const deadline = profileDeadline();
    const attemptRows = [];
    let lastAttempt = failedAttempt("timeout");

    for (let attemptNumber = 1; attemptNumber <= PROFILE_MAX_ATTEMPTS; attemptNumber++) {
      let backoffMs = 0;
      if (attemptNumber > 1) {
        backoffMs = PROFILE_BACKOFF_BEFORE_RETRY_MS[attemptNumber - 2];
      }

      if (!hasTimeForAttempt(deadline, backoffMs)) {
        break;
      }

      if (backoffMs > 0) {
        await sharedDom.sleep(backoffMs);
      }

      const attemptStartedAt = Date.now();
      lastAttempt = await profileAttempt(Math.min(PROFILE_ATTEMPT_TIMEOUT_MS, deadline - Date.now()));
      const attemptRow = {
        attempt: attemptNumber,
        outcome: lastAttempt.outcome,
        http_status: lastAttempt.httpStatus,
        latency_ms: Date.now() - attemptStartedAt
      };
      attemptRows.push(attemptRow);
      emitTelemetry(Object.assign({ event_name: "auth_profile_attempt" }, attemptRow));

      if (FINAL_PROFILE_OUTCOMES.includes(lastAttempt.outcome)) {
        break;
      }
    }

    const error = profileErrorFor(lastAttempt.outcome);
    const result = { outcome: lastAttempt.outcome, attempts: attemptRows.length, total_ms: Date.now() - startedAt };
    if (error !== null) {
      result.error = error;
    }
    emitTelemetry(Object.assign({ event_name: "auth_profile_result" }, result));

    const profileDiag = { attempts: attemptRows, result };
    if (error === null) {
      return { loggedIn: true, profile: lastAttempt.profile, profileDiag };
    }

    return { loggedIn: true, profileFailure: { error, reason: lastAttempt.outcome }, profileDiag };
  }

  function unexpectedProfileFailure() {
    const result = { outcome: "invalid_response", attempts: 0, total_ms: 0, error: ERR_PROFILE_INDETERMINATE };
    emitTelemetry(Object.assign({ event_name: "auth_profile_result" }, result));

    return {
      loggedIn: true,
      profileFailure: { error: ERR_PROFILE_INDETERMINATE, reason: "invalid_response" },
      profileDiag: { attempts: [], result }
    };
  }

  function withProfile(status) {
    if (!status.loggedIn) {
      return status;
    }

    return readProfile().catch(unexpectedProfileFailure);
  }

  const loggedInStatus = new Promise((resolve) => {
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

  return loggedInStatus.then(withProfile);
})();
