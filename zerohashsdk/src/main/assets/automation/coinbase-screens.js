// Coinbase screens that end an automation (AUTH-4657). Each consumer maps the
// ids it cares about to its own result and only asks about those, so a new
// entry here changes nothing until a consumer maps it. Keep in step with the
// extension's screens.ts.
(function () {
  if (window.__zhCoinbaseScreens) return;

  var DIALOG = '[role="dialog"][aria-modal="true"]';
  var STEP_INACTIVE = '[data-testid^="step-"][data-testid$="-inactive"]';
  // Only exist after the send was submitted: a screen there is not a rejection.
  var POST_SUBMIT =
    '[data-testid="step-twoFactorStep-active"], [data-testid="step-statusStep-active"], ' +
    '[data-testid="step-riskSelfServeStep-active"], [data-testid="step-systemCancellationStep-active"], ' +
    '[data-testid="fallback"]';
  var OUTAGE_IMG = 'img[src*="/ui-infra/illustration/"][src*="/pictogram/svg/"][src*="/outage-"]';

  // `anchors` count inside the flow's dialog; `pageAnchors` anywhere.
  var SCREENS = [
    {
      id: "unavailable",
      anchors: {
        send: [OUTAGE_IMG, '[data-testid="send-receive-error-fallback-status-page-link"]'],
        receive: [OUTAGE_IMG]
      },
      pageAnchors: ['[data-testid="app-level-error-page"]']
    }
  ];

  var STABLE_MS = 300;
  var MIN_OPACITY = 0.05;
  var firstSeenAt = {};
  var reported = {};

  // Coinbase mounts screens at opacity 0 during transitions and may recover.
  function userVisible(el) {
    var r = el.getBoundingClientRect();
    if (!(r.width > 0 && r.height > 0)) return false;
    if (r.bottom <= 0 || r.right <= 0 || r.top >= window.innerHeight || r.left >= window.innerWidth) return false;
    var opacity = 1;
    for (var n = el; n && n !== document.documentElement; n = n.parentElement) {
      var cs = window.getComputedStyle(n);
      if (cs.display === "none" || cs.visibility === "hidden") return false;
      opacity *= parseFloat(cs.opacity || "1");
      if (n !== el && /(hidden|clip|auto|scroll)/.test(String(cs.overflow) + String(cs.overflowY))) {
        var p = n.getBoundingClientRect();
        if (r.bottom <= p.top || r.top >= p.bottom || r.right <= p.left || r.left >= p.right) return false;
      }
    }
    return opacity > MIN_OPACITY;
  }

  function inScope(el) {
    return !!el.closest(DIALOG) && !el.closest(STEP_INACTIVE) && !el.closest(POST_SUBMIT);
  }

  function anyVisible(selectors, scoped) {
    for (var a = 0; a < selectors.length; a++) {
      var nodes = document.querySelectorAll(selectors[a]);
      for (var i = 0; i < nodes.length; i++) {
        if ((!scoped || inScope(nodes[i])) && userVisible(nodes[i])) return true;
      }
    }
    return false;
  }

  function candidates(flow, ids) {
    return SCREENS.filter(function (s) {
      return s.anchors[flow] && (!ids || ids.indexOf(s.id) !== -1);
    });
  }

  function isVisible(screen, flow) {
    return anyVisible(screen.anchors[flow], true) || anyVisible(screen.pageAnchors || [], false);
  }

  function visibleNow(flow, ids) {
    var list = candidates(flow, ids);
    for (var i = 0; i < list.length; i++) if (isVisible(list[i], flow)) return list[i].id;
    return null;
  }

  // First screen visible for STABLE_MS, or null. `now` is for tests.
  function confirmed(flow, ids, now) {
    var t = typeof now === "number" ? now : Date.now();
    var list = candidates(flow, ids);
    var hit = null;
    for (var i = 0; i < list.length; i++) {
      var key = flow + ":" + list[i].id;
      if (!isVisible(list[i], flow)) {
        delete firstSeenAt[key];
        continue;
      }
      if (firstSeenAt[key] === undefined) firstSeenAt[key] = t;
      if (!hit && t - firstSeenAt[key] >= STABLE_MS) hit = list[i].id;
    }
    if (hit && !reported[flow + ":" + hit]) {
      reported[flow + ":" + hit] = true;
      if (window.__zhTelemetry) window.__zhTelemetry.breadcrumb("screen", flow + ":" + hit);
    }
    return hit;
  }

  function confirm(flow, ids, windowMs) {
    var end = Date.now() + (windowMs || 600);
    return new Promise(function (resolve) {
      (function tick() {
        var id = confirmed(flow, ids);
        if (id) return resolve(id);
        if (Date.now() >= end) return resolve(null);
        setTimeout(tick, 100);
      })();
    });
  }

  function reset(flow) {
    for (var k in firstSeenAt) if (k.indexOf(flow + ":") === 0) delete firstSeenAt[k];
    for (var r in reported) if (r.indexOf(flow + ":") === 0) delete reported[r];
  }

  // One guard per flow run: idle -> armed -> halted | closed. The consumer arms
  // it once its own preflight is done and closes it when nothing may be
  // reported any more. While armed, a mapped screen halts it: run() rejects
  // with the screen error and check() throws the stop error, which is how the
  // consumer's primitives stop the abandoned chain. Detection is read from
  // `this` (the registry) at call time.
  var WATCH_MS = 150;

  function guard(flow, opts) {
    var api = this;
    var ids = (opts && opts.ids) || [];
    var submittedWhen = (opts && opts.submittedWhen) || null;
    var stopError = null;
    var g = { phase: "idle" };
    api.reset(flow);

    function move(to, why) {
      var from = g.phase;
      g.phase = to;
      if (window.__zhTelemetry) {
        window.__zhTelemetry.breadcrumb("screen-guard", flow + ": " + from + " -> " + to + (why ? " (" + why + ")" : ""));
      }
    }

    function halt(id) {
      stopError = new Error(flow + "/halted: Coinbase showed its " + id + " screen");
      stopError.zhHalted = id;
      move("halted", id);
    }

    function screenError(id) {
      var e = new Error(flow + "/screen: Coinbase showed its " + id + " screen");
      e.zhScreen = id;
      return e;
    }

    g.arm = function () {
      if (g.phase !== "idle") throw new Error("screen-guard: arm() in " + g.phase);
      move("armed");
    };

    g.close = function (reason) {
      if (g.phase === "idle" || g.phase === "armed") move("closed", reason);
    };

    g.check = function () {
      if (stopError) throw stopError;
    };

    g.run = function (promise) {
      var settled = false;
      var watch = new Promise(function (_, reject) {
        (function tick() {
          if (settled || g.phase === "closed" || g.phase === "halted") return;
          if (g.phase === "armed") {
            if (submittedWhen && submittedWhen()) return g.close("post-send-gate");
            var id = api.confirmed(flow, ids);
            if (id) {
              halt(id);
              return reject(screenError(id));
            }
          }
          setTimeout(tick, WATCH_MS);
        })();
      });
      return Promise.race([promise, watch]).then(
        function (v) { settled = true; return v; },
        function (e) { settled = true; throw e; }
      );
    };

    // A wait can time out on a screen before the watcher's STABLE_MS elapses.
    g.afterFailure = async function (windowMs) {
      if (g.phase !== "armed") return null;
      if (submittedWhen && submittedWhen()) {
        g.close("post-send-gate");
        return null;
      }
      var id = await api.confirm(flow, ids, windowMs);
      if (id) halt(id);
      else g.close("failed");
      return id;
    };

    return g;
  }

  window.__zhCoinbaseScreens = {
    visibleNow: visibleNow,
    confirmed: confirmed,
    confirm: confirm,
    reset: reset,
    guard: guard,
    SCREENS: SCREENS,
    STABLE_MS: STABLE_MS
  };
})();
