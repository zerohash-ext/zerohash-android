// withdraw.js + a fake coinbase-screens.js (AUTH-4657).
import assert from "node:assert";
import { test } from "node:test";
import { loadWithdraw, rehome, SEL, WITHDRAW_SOURCE } from "./withdraw-shim.mjs";
import { realGuard } from "./screens-gate-shim.mjs";

const PARAMS = { address: "0xabc", asset: "ETH", network: "ethereum", amount: "max" };
const UNAVAILABLE = { state: "rejected", reason: "send_unavailable" };
// The shim's waitFor stub throws a host-realm Error, so start() passes it through unwrapped.
const ORIGINAL_FAILURE = /waitFor is not stubbed/;

// `watch`: what the watcher sees; `after`: what the post-failure check sees.
// Like the real registry, it only returns an id the caller asked for.
function fakeScreens({ watch = null, after = null } = {}) {
  const calls = { reset: 0, confirm: 0, asked: [] };
  return {
    calls,
    reset: () => { calls.reset += 1; },
    confirmed: (flow, ids) => {
      calls.asked.push({ flow, ids: [...ids] });
      return watch && ids.includes(watch) ? watch : null;
    },
    confirm: async (flow, ids) => {
      calls.confirm += 1;
      return after && ids.includes(after) ? after : null;
    },
    guard: realGuard(),
  };
}

test("the screen ends a stuck send fast, asking only about the mapped ids", async () => {
  const screens = fakeScreens({ watch: "unavailable" });
  const { window } = loadWithdraw({ present: [] }, { screens });
  const t0 = Date.now();
  assert.deepStrictEqual(rehome(await window.__zhWithdraw.start(PARAMS)), UNAVAILABLE);
  assert.ok(Date.now() - t0 < 2000, `took ${Date.now() - t0}ms`);
  assert.strictEqual(screens.calls.reset, 1);
  assert.deepStrictEqual(screens.calls.asked[0], { flow: "send", ids: ["unavailable"] });
});

test("a step that fails while the screen is up gets the mapped result", async () => {
  const screens = fakeScreens({ after: "unavailable" });
  const { window } = loadWithdraw({ present: [SEL.RECIPIENT_INPUT] }, { screens });
  assert.deepStrictEqual(rehome(await window.__zhWithdraw.start(PARAMS)), UNAVAILABLE);
});

test("no screen, an unmapped screen or no registry: the failure is untouched", async () => {
  for (const screens of [fakeScreens(), fakeScreens({ watch: "send-blocked", after: "send-blocked" }), undefined]) {
    const { window } = loadWithdraw({ present: [SEL.RECIPIENT_INPUT] }, { screens });
    await assert.rejects(window.__zhWithdraw.start(PARAMS), ORIGINAL_FAILURE);
  }
});

test("a post-send gate on screen means the send may be in: nothing is reported", async () => {
  const screens = fakeScreens({ watch: "unavailable", after: "unavailable" });
  const { window, internals } = loadWithdraw({ present: [SEL.RECIPIENT_INPUT, SEL.OTP_CONTAINER] }, { screens });
  await assert.rejects(window.__zhWithdraw.start(PARAMS), ORIGINAL_FAILURE);
  assert.strictEqual(screens.calls.confirm, 0);
  assert.strictEqual(internals.guard().phase, "closed");
});

test("a named blocker keeps its own reason", async () => {
  const { window } = loadWithdraw({ present: [SEL.STEP_WBL_HOLD] }, { screens: fakeScreens({ after: "unavailable" }) });
  assert.deepStrictEqual(rehome(await window.__zhWithdraw.start(PARAMS)), { state: "rejected", reason: "funds_not_available" });
});

test("once halted, no click lands", async () => {
  const screens = fakeScreens({ watch: "unavailable" });
  const { window, internals } = loadWithdraw({ present: [] }, { screens });
  assert.deepStrictEqual(rehome(await window.__zhWithdraw.start(PARAMS)), UNAVAILABLE);
  assert.strictEqual(internals.guard().phase, "halted");
  let clicks = 0;
  const el = { click: () => { clicks += 1; } };
  await assert.rejects(internals.humanClick({}), /send\/halted/);
  assert.throws(() => internals.plainClick(el), /send\/halted/);
  assert.strictEqual(clicks, 0);
});

test("once halted, the abandoned chain stops polling the page", async () => {
  const { window, document } = loadWithdraw({ present: [] }, { screens: fakeScreens({ watch: "unavailable" }) });
  let queries = 0;
  const query = document.querySelector.bind(document);
  document.querySelector = (sel) => { queries += 1; return query(sel); };
  await window.__zhWithdraw.start(PARAMS);
  const atReturn = queries;
  await new Promise((r) => setTimeout(r, 600));
  assert.ok(queries - atReturn <= 1, `still polling: ${queries - atReturn} queries after start returned`);
});

// Source-order guarantees a fake DOM cannot drive.
function body(name) {
  const start = WITHDRAW_SOURCE.indexOf(name);
  assert.ok(start >= 0, `${name} not found`);
  return WITHDRAW_SOURCE.slice(start, WITHDRAW_SOURCE.indexOf("\n  }\n", start));
}

test("confirmAndSend closes the guard right before the Send now click and when already past confirm", () => {
  const confirm = body("async function confirmAndSend(");
  const lookup = confirm.indexOf("var sendBtn = ");
  const click = confirm.indexOf("await humanClick(sendBtn);");
  const close = confirm.indexOf('closeSendGuard("send-now");', lookup);
  assert.ok(lookup > 0 && close > lookup && close < click, "the guard must close right before the Send now click");
  const branch = confirm.slice(confirm.indexOf("if (which !== SEL.SEND_NOW)"));
  assert.ok(branch.indexOf('closeSendGuard("post-send-gate");') >= 0);
  assert.ok(branch.indexOf('closeSendGuard("post-send-gate");') < branch.indexOf("return readSendPreview();"));
});

test("only the pre-submit chain runs under the guard", () => {
  const start = WITHDRAW_SOURCE.slice(WITHDRAW_SOURCE.indexOf("start: async function"));
  const raced = start.slice(start.indexOf("runPreSubmit(("), start.indexOf("})());"));
  assert.ok(raced.includes("confirmAndSend(params.address)"));
  assert.ok(!raced.includes("detectAndHandle2fa"), "2FA runs after Send now");
  const cont = WITHDRAW_SOURCE.slice(WITHDRAW_SOURCE.indexOf("continue: async function"));
  assert.ok(!/runPreSubmit|openSendGuard/.test(cont.slice(0, cont.indexOf("\n    },\n"))), "continue runs after Send now");
});
