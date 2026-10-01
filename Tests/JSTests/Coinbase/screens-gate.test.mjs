// coinbase-screens.js (AUTH-4657).
import assert from "node:assert";
import { test } from "node:test";
import { dialog, el, loadGate, OUTAGE_DARK, outageImg, step } from "./screens-gate-shim.mjs";

const UNAV = ["unavailable"];

test("the outage pictogram inside the flow dialog is the unavailable screen", () => {
  for (const img of [outageImg(), outageImg({ src: OUTAGE_DARK })]) {
    const { gate } = loadGate(dialog(step("step-assetDisplay-active", img)));
    assert.strictEqual(gate.visibleNow("send", UNAV), "unavailable");
    assert.strictEqual(gate.visibleNow("receive", UNAV), "unavailable");
  }
});

test("out of scope: outside a dialog, outgoing steps, post-submit steps", () => {
  const cases = [
    el({ testid: "balance-summary-error-fallback" }, outageImg()),
    dialog(step("step-assetSelection-inactive", outageImg())),
    ...["step-twoFactorStep-active", "step-statusStep-active", "step-riskSelfServeStep-active",
      "step-systemCancellationStep-active", "fallback"].map((id) => dialog(step(id, outageImg())))
  ];
  for (const body of cases) assert.strictEqual(loadGate(body).gate.visibleNow("send", UNAV), null);
});

test("not visible: faded, hidden, zero-size, off-viewport, scrolled out", () => {
  const cases = [
    dialog(el({ style: { opacity: "0" } }, outageImg())),
    dialog(el({ style: { display: "none" } }, outageImg())),
    dialog(el({ style: { visibility: "hidden" } }, outageImg())),
    dialog(outageImg({ rect: { width: 0 } })),
    dialog(outageImg({ rect: { top: 900 } })),
    dialog(outageImg({ rect: { top: -100 } })),
    dialog(el({ style: { overflow: "hidden" }, rect: { top: 0, left: 0, width: 400, height: 50 } }, outageImg({ rect: { top: 200 } })))
  ];
  for (const body of cases) assert.strictEqual(loadGate(body).gate.visibleNow("send", UNAV), null);
});

test("the Send status link counts for Send only; the app-level page for both", () => {
  const link = el({ tag: "button", testid: "send-receive-error-fallback-status-page-link", rect: { top: 300, width: 80, height: 20 } });
  let { gate } = loadGate(dialog(link));
  assert.strictEqual(gate.visibleNow("send", UNAV), "unavailable");
  assert.strictEqual(gate.visibleNow("receive", UNAV), null);
  ({ gate } = loadGate(el({ testid: "app-level-error-page", rect: { top: 0, left: 0, width: 400, height: 800 } })));
  assert.strictEqual(gate.visibleNow("send", UNAV), "unavailable");
  assert.strictEqual(gate.visibleNow("receive", UNAV), "unavailable");
});

test("a healthy page matches nothing", () => {
  const { gate } = loadGate(dialog(step("step-amountEntry-active", el({ testid: "currency-input" }))));
  assert.strictEqual(gate.visibleNow("send", UNAV), null);
});

test("confirmed needs STABLE_MS of visibility, reports once, and a gap restarts the clock", () => {
  const g = loadGate(dialog(outageImg()));
  const t0 = 1_000_000;
  assert.strictEqual(g.gate.confirmed("send", UNAV, t0), null);
  assert.strictEqual(g.gate.confirmed("send", UNAV, t0 + g.gate.STABLE_MS - 1), null);
  assert.strictEqual(g.gate.confirmed("send", UNAV, t0 + g.gate.STABLE_MS), "unavailable");
  assert.strictEqual(g.gate.confirmed("send", UNAV, t0 + g.gate.STABLE_MS + 150), "unavailable");
  assert.deepStrictEqual(g.breadcrumbs, ["screen:send:unavailable"]);
  g.setBody(dialog(el({ style: { opacity: "0" } }, outageImg())));
  assert.strictEqual(g.gate.confirmed("send", UNAV, t0 + 600), null);
  g.setBody(dialog(outageImg()));
  assert.strictEqual(g.gate.confirmed("send", UNAV, t0 + 700), null);
  assert.strictEqual(g.gate.confirmed("send", UNAV, t0 + 700 + g.gate.STABLE_MS), "unavailable");
});

test("reset restarts the clock", () => {
  const { gate } = loadGate(dialog(outageImg()));
  gate.confirmed("send", UNAV, 0);
  gate.reset("send");
  assert.strictEqual(gate.confirmed("send", UNAV, gate.STABLE_MS), null);
});

test("confirm resolves the id once stable, null on a healthy page", async () => {
  assert.strictEqual(await loadGate(dialog(outageImg())).gate.confirm("send", UNAV, 1000), "unavailable");
  assert.strictEqual(await loadGate(dialog(el({ testid: "currency-input" }))).gate.confirm("send", UNAV, 250), null);
});

test("a caller is only told about the ids it asks for", () => {
  const { gate } = loadGate(dialog(outageImg()));
  assert.strictEqual(gate.visibleNow("send", []), null);
  assert.strictEqual(gate.visibleNow("send", ["something-else"]), null);
});

test("a new entry is found without touching the loop", () => {
  const { gate } = loadGate(dialog(step("step-sendBlocked-active", el({ tag: "h2" }))));
  gate.SCREENS.push({ id: "send-blocked", anchors: { send: ['[data-testid="step-sendBlocked-active"]'] } });
  assert.strictEqual(gate.visibleNow("send", ["send-blocked"]), "send-blocked");
  assert.strictEqual(gate.visibleNow("receive", ["send-blocked"]), null);
});

// The guard (AUTH-4657): the real code on a registry whose detection is faked.
function guardWith({ confirmed = () => null, confirm = async () => null } = {}) {
  const g = loadGate();
  const api = { reset: () => {}, confirmed, confirm, guard: g.gate.guard };
  return { api, breadcrumbs: g.breadcrumbs };
}
const tick = (ms) => new Promise((r) => setTimeout(r, ms));

test("guard phases: idle -> armed -> closed; close is idempotent; arm only from idle", () => {
  const { api, breadcrumbs } = guardWith();
  const g = api.guard("send", { ids: UNAV });
  assert.strictEqual(g.phase, "idle");
  g.arm();
  assert.strictEqual(g.phase, "armed");
  assert.throws(() => g.arm(), /screen-guard: arm\(\) in armed/);
  g.close("send-now");
  g.close("again");
  assert.strictEqual(g.phase, "closed");
  assert.throws(() => g.arm(), /screen-guard: arm\(\) in closed/);
  g.check();
  assert.deepStrictEqual(breadcrumbs, [
    "screen-guard:send: idle -> armed",
    "screen-guard:send: armed -> closed (send-now)"
  ]);
});

test("the watcher is silent while idle, halts once armed, and the stop error names the screen", async () => {
  let asks = 0;
  const { api, breadcrumbs } = guardWith({
    confirmed: (flow, ids) => { asks += 1; return ids.includes("unavailable") ? "unavailable" : null; }
  });
  const g = api.guard("receive", { ids: UNAV });
  const running = g.run(new Promise(() => {}));
  await tick(400);
  assert.strictEqual(asks, 0);
  g.arm();
  await assert.rejects(running, (e) => e.zhScreen === "unavailable" && /^receive\/screen: /.test(e.message));
  assert.strictEqual(g.phase, "halted");
  assert.throws(() => g.check(), (e) => e.zhHalted === "unavailable" && /^receive\/halted: /.test(e.message));
  g.close("finished");
  assert.strictEqual(g.phase, "halted");
  assert.ok(breadcrumbs.includes("screen-guard:receive: armed -> halted (unavailable)"), breadcrumbs.join("|"));
});

test("run passes the flow result through and stops watching", async () => {
  let asks = 0;
  const { api } = guardWith({ confirmed: () => { asks += 1; return null; } });
  const g = api.guard("send", { ids: UNAV });
  g.arm();
  assert.strictEqual(await g.run(Promise.resolve("done")), "done");
  const after = asks;
  await tick(400);
  assert.ok(asks - after <= 1, `still watching: ${asks - after} asks`);
});

test("a post-send gate closes an armed guard instead of reporting", async () => {
  const { api } = guardWith({ confirmed: () => "unavailable" });
  const g = api.guard("send", { ids: UNAV, submittedWhen: () => true });
  g.arm();
  assert.strictEqual(await g.run(new Promise((r) => setTimeout(() => r("flow"), 300))), "flow");
  assert.strictEqual(g.phase, "closed");
});

test("afterFailure checks once while armed and never in another phase", async () => {
  let confirms = 0;
  let { api } = guardWith({ confirm: async (flow, ids, ms) => { confirms += 1; assert.strictEqual(ms, 600); return "unavailable"; } });
  let g = api.guard("send", { ids: UNAV });
  assert.strictEqual(await g.afterFailure(600), null);
  g.arm();
  assert.strictEqual(await g.afterFailure(600), "unavailable");
  assert.strictEqual(g.phase, "halted");
  assert.strictEqual(await g.afterFailure(600), null);
  assert.strictEqual(confirms, 1);
  ({ api } = guardWith());
  g = api.guard("send", { ids: UNAV });
  g.arm();
  assert.strictEqual(await g.afterFailure(600), null);
  assert.strictEqual(g.phase, "closed");
  ({ api } = guardWith({ confirm: async () => { confirms += 1; return "unavailable"; } }));
  g = api.guard("send", { ids: UNAV, submittedWhen: () => true });
  g.arm();
  assert.strictEqual(await g.afterFailure(600), null);
  assert.strictEqual(g.phase, "closed");
  assert.strictEqual(confirms, 1);
});

test("a new guard restarts the registry clock for its flow", () => {
  const resets = [];
  const { api } = guardWith();
  api.reset = (flow) => resets.push(flow);
  api.guard("receive", { ids: UNAV });
  assert.deepStrictEqual(resets, ["receive"]);
});
