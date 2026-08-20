// Telemetry buffer injected before every automation script. Call sites emit via
// window.__zhTelemetry.emit({...}); the wrapper drains it at settle. Never emits PII.
window.__zhTelemetry = window.__zhTelemetry || (function () {
  var rows = [];
  var seq = 0;
  var enabled = false;
  var phaseIndex = 0;
  var dispatchStart = 0;
  // Capture Date.now before page code can shim it (same reasoning as the native
  // clock captures in the extension's injected runtime).
  var nativeNow = Date.now.bind(Date);
  return {
    // The wrapper calls enable(true) once per dispatch, which also resets the phase
    // counter so each dispatch has a fresh timeline.
    enable: function (on) {
      enabled = !!on;
      if (enabled) { phaseIndex = 0; dispatchStart = nativeNow(); }
    },
    isEnabled: function () { return enabled; },
    emit: function (input) {
      if (!enabled) return;
      var row = {};
      for (var k in input) {
        if (Object.prototype.hasOwnProperty.call(input, k)) row[k] = input[k];
      }
      row.at = nativeNow();
      row.seq = ++seq;
      row.realm = "injected";
      rows.push(row);
    },
    // A milestone in the flow, emitting extension_handler_phase_reached. `note` is
    // an optional short tag (never PII).
    breadcrumb: function (phase, note) {
      if (!enabled) return;
      this.emit({
        event_name: "extension_handler_phase_reached",
        phase: String(phase),
        note: (note === undefined || note === null) ? null : String(note),
        phase_index: ++phaseIndex,
        since_dispatch_ms: nativeNow() - dispatchStart
      });
    },
    drain: function () { var out = rows.slice(); rows = []; return out; }
  };
})();
