(function () {
  if (window.__zhCoinbaseIdv) return;

  var VERIFY_AUTHORIZATION_URL = "https://login.coinbase.com/api/uis/v1/verify-authorization";
  var IDV_ATTEMPTS_URL = "https://login.coinbase.com/api/v2/identity-verifications/attempts-remaining";

  var IDV_REQUIREMENT_COMPONENT = "COMPONENT_EXPERIENCE_IDENTITY_DOCUMENT_VERIFICATION";
  var PRIOR_ATTEMPT_COMPONENT = "COMPONENT_EXPERIENCE_GUIDANCE_SCREEN";
  var AUTHORIZATION_STATUS_COMPLETE = "ACTION_AUTHORIZATION_STATUS_COMPLETE";

  var REASON_IDV_PENDING = "idv_pending";
  var REASON_IDV_FAILED = "idv_failed";
  var REASON_WITHOUT_PRIOR_ATTEMPT_EVIDENCE = REASON_IDV_PENDING;

  var ERROR_CODE_IDV_PENDING = "IDV_PENDING";
  var ERROR_CODE_IDV_FAILED = "IDV_FAILED";

  var AUTHORIZATION_BLOCKED_ON_IDV = "blocked-on-idv";
  var AUTHORIZATION_CLEAR = "clear";
  var AUTHORIZATION_UNKNOWN = "unknown";

  var REQUEST_TIMEOUT_MS = 5000;
  var COINBASE_CLIENT_HEADERS = { accept: "application/json", "x-cb-platform": "web" };

  var IDV_BLOCK_ANCHORS = [
    '[data-testid^="policy-restriction-enforcer"]',
    '[data-testid^="idv-guidance-"]',
    '[data-testid="onboarding-guidance-container"]',
    '[data-testid="onboarding_fs_loader"]',
    '[data-testid="identity-access-view-wrapper"]',
    '[data-testid="post-onboarding-navbar-actions"]'
  ];

  var PRIOR_ATTEMPT_ANCHORS = [
    '[data-testid="onboarding-guidance-container"]',
    '[data-testid^="guid-action-btn-"]'
  ];

  function breadcrumb(phase, note) {
    if (window.__zhTelemetry) window.__zhTelemetry.breadcrumb(phase, note);
  }

  function isCloudflareChallenge(status, headers, bodyText) {
    if (status === 403 || status === 429) return true;
    if (headers && headers.get && headers.get("cf-mitigated")) return true;
    if (typeof bodyText === "string" && bodyText.indexOf("_cf_chl_opt") !== -1) return true;
    if (window._cf_chl_opt) return true;
    return false;
  }

  async function fetchJsonWithoutThrowing(url, init) {
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, REQUEST_TIMEOUT_MS);
    try {
      var response = await fetch(url, Object.assign({}, init, {
        credentials: "include",
        signal: controller.signal
      }));
      var text = await response.text();
      if (isCloudflareChallenge(response.status, response.headers, text)) {
        return { ok: false, note: "challenge" };
      }
      if (!response.ok) return { ok: false, note: "http-" + response.status };
      try {
        return { ok: true, body: JSON.parse(text) };
      } catch (parseError) {
        return { ok: false, note: "unparseable" };
      }
    } catch (requestError) {
      var aborted = requestError && requestError.name === "AbortError";
      return { ok: false, note: aborted ? "timeout" : "network" };
    } finally {
      clearTimeout(timer);
    }
  }

  function firstMatchingAnchor(anchors) {
    for (var i = 0; i < anchors.length; i++) {
      if (document.querySelector(anchors[i])) return anchors[i];
    }
    return null;
  }

  function componentIdsInclude(nextSteps, componentId) {
    if (!Array.isArray(nextSteps)) return false;
    for (var i = 0; i < nextSteps.length; i++) {
      if (nextSteps[i] && nextSteps[i].component_id === componentId) return true;
    }
    return false;
  }

  function priorAttemptFromBlockLabel(nextSteps) {
    return componentIdsInclude(nextSteps, PRIOR_ATTEMPT_COMPONENT) ? true : null;
  }

  function priorAttemptFromVisibleScreen() {
    return firstMatchingAnchor(PRIOR_ATTEMPT_ANCHORS) ? true : null;
  }

  async function priorAttemptFromAttemptCount() {
    var result = await fetchJsonWithoutThrowing(IDV_ATTEMPTS_URL, {
      method: "GET",
      headers: COINBASE_CLIENT_HEADERS
    });
    if (!result.ok) return null;
    var data = result.body && result.body.data;
    if (!data || typeof data.number_of_idv_attempts !== "number") return null;
    return data.number_of_idv_attempts > 0;
  }

  async function reasonForBlock(nextSteps) {
    if (priorAttemptFromBlockLabel(nextSteps)) {
      breadcrumb("idv-classify", "block-label:prior-attempt");
      return REASON_IDV_FAILED;
    }
    if (priorAttemptFromVisibleScreen()) {
      breadcrumb("idv-classify", "screen:prior-attempt");
      return REASON_IDV_FAILED;
    }
    var byAttemptCount = await priorAttemptFromAttemptCount();
    if (byAttemptCount === true) {
      breadcrumb("idv-classify", "attempts:prior-attempt");
      return REASON_IDV_FAILED;
    }
    breadcrumb("idv-classify", byAttemptCount === false ? "attempts:none" : "no-evidence");
    return REASON_WITHOUT_PRIOR_ATTEMPT_EVIDENCE;
  }

  function errorCodeForReason(reason) {
    if (reason === REASON_IDV_PENDING) return ERROR_CODE_IDV_PENDING;
    if (reason === REASON_IDV_FAILED) return ERROR_CODE_IDV_FAILED;
    return null;
  }

  async function readAuthorization(action) {
    var result = await fetchJsonWithoutThrowing(VERIFY_AUTHORIZATION_URL, {
      method: "POST",
      headers: Object.assign({}, COINBASE_CLIENT_HEADERS, { "content-type": "application/json" }),
      body: JSON.stringify({ action: action, df_pro_sealed_result: "" })
    });
    if (!result.ok) {
      breadcrumb("idv-preflight", action + ":" + result.note);
      return { status: AUTHORIZATION_UNKNOWN, nextSteps: null };
    }
    var body = result.body;
    if (!body || typeof body !== "object") {
      return { status: AUTHORIZATION_UNKNOWN, nextSteps: null };
    }

    var nextSteps = body.next_steps;
    if (body.status === AUTHORIZATION_STATUS_COMPLETE && (!nextSteps || nextSteps.length === 0)) {
      breadcrumb("idv-preflight", action + ":clear");
      return { status: AUTHORIZATION_CLEAR, nextSteps: null };
    }
    if (!componentIdsInclude(nextSteps, IDV_REQUIREMENT_COMPONENT)) {
      breadcrumb("idv-preflight", action + ":other-requirement");
      return { status: AUTHORIZATION_UNKNOWN, nextSteps: null };
    }
    return { status: AUTHORIZATION_BLOCKED_ON_IDV, nextSteps: nextSteps };
  }

  async function blockedReasonForAction(action) {
    var authorization = await readAuthorization(action);
    if (authorization.status !== AUTHORIZATION_BLOCKED_ON_IDV) return null;
    var reason = await reasonForBlock(authorization.nextSteps);
    breadcrumb("idv-preflight", action + ":blocked:" + reason);
    return reason;
  }

  async function blockedReasonFromVisibleDom() {
    var anchor = firstMatchingAnchor(IDV_BLOCK_ANCHORS);
    if (!anchor) return null;
    breadcrumb("idv-dom-block", anchor);
    return await reasonForBlock(null);
  }

  window.__zhCoinbaseIdv = {
    blockedReasonForAction: blockedReasonForAction,
    blockedReasonFromVisibleDom: blockedReasonFromVisibleDom,
    reasonForBlock: reasonForBlock,
    priorAttemptFromVisibleScreen: priorAttemptFromVisibleScreen,
    errorCodeForReason: errorCodeForReason,
    AUTHORIZATION_CLEAR: AUTHORIZATION_CLEAR,
    AUTHORIZATION_UNKNOWN: AUTHORIZATION_UNKNOWN,
    REASON_IDV_PENDING: REASON_IDV_PENDING,
    REASON_IDV_FAILED: REASON_IDV_FAILED,
    ERROR_CODE_IDV_PENDING: ERROR_CODE_IDV_PENDING,
    ERROR_CODE_IDV_FAILED: ERROR_CODE_IDV_FAILED
  };
})();
