// get-deposit-address.js + a fake coinbase-screens.js (AUTH-4657), Android.
// No deposit shim exists here, so this loads the script itself with waits capped.
import assert from "node:assert";
import { existsSync, readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import { realGuard } from "./screens-gate-shim.mjs";

const MODULE = ["zerohashsdk", "connectsdk"].find((m) =>
  existsSync(fileURLToPath(new URL(`../../../${m}/src/main/assets/automation/get-deposit-address.js`, import.meta.url)))
);
const asset = (name) =>
  readFileSync(fileURLToPath(new URL(`../../../${MODULE}/src/main/assets/automation/${name}`, import.meta.url)), "utf8");
const DOM_HELPERS = asset("dom-helpers.js");
const DEPOSIT = asset("get-deposit-address.js");
const WAIT_MS = 60;

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

function runDeposit({ screens, idvCode = null, idvDelayMs = 0, dom = null } = {}) {
  const window = {};
  if (screens) window.__zhCoinbaseScreens = screens;
  if (idvCode) {
    window.__zhCoinbaseIdv = {
      blockedReasonForAction: () => new Promise((r) => setTimeout(() => r("blocked"), idvDelayMs)),
      blockedReasonFromVisibleDom: async () => null,
      errorCodeForReason: () => idvCode
    };
  }
  const document = { querySelector: () => null, querySelectorAll: () => [] };
  const ctx = vm.createContext({ window, document, params: { asset: "ETH", network: "ethereum" }, setTimeout, clearTimeout, console });
  vm.runInContext(DOM_HELPERS, ctx);
  const later = (v) => new Promise((r) => setTimeout(() => r(v), WAIT_MS));
  Object.assign(window.__zhDom, {
    sleep: () => later(),
    waitUntil: () => later(null),
    waitFor: (sel) => later().then(() => { throw new Error("element_not_found:" + sel); })
  });
  if (dom) Object.assign(window.__zhDom, dom);
  return vm.runInContext(DEPOSIT, ctx);
}

test("the screen fails the call with RECEIVE_UNAVAILABLE, asking only about mapped ids", async () => {
  const screens = fakeScreens({ watch: "unavailable" });
  // The guard's first armed tick is 150 ms after arm(); keep the entry wait open past it.
  const dom = { waitUntil: () => new Promise((r) => setTimeout(() => r(null), 300)) };
  await assert.rejects(runDeposit({ screens, dom }), (e) => e.message === "RECEIVE_UNAVAILABLE");
  assert.strictEqual(screens.calls.reset, 1);
  assert.deepStrictEqual(screens.calls.asked[0], { flow: "receive", ids: ["unavailable"] });
});

test("a wait that times out on the screen becomes RECEIVE_UNAVAILABLE", async () => {
  await assert.rejects(runDeposit({ screens: fakeScreens({ after: "unavailable" }) }), (e) => e.message === "RECEIVE_UNAVAILABLE");
});

test("no screen, an unmapped screen or no registry: the original error stays", async () => {
  for (const screens of [fakeScreens(), fakeScreens({ watch: "send-blocked", after: "send-blocked" }), undefined]) {
    await assert.rejects(runDeposit({ screens }), /receive_entry_not_found/);
  }
});

test("once the screen ends the call, the run clicks nothing more", async () => {
  // Every step is found, so an unguarded run keeps clicking through the flow.
  const found = { click() {}, focus() {}, getAttribute: () => null, textContent: "" };
  const later = (v) => new Promise((r) => setTimeout(() => r(v), WAIT_MS));
  let clicks = 0;
  const dom = {
    $: () => found,
    waitUntil: (find) => later().then(() => find()),
    waitFor: () => later(found),
    stepPrimaryButton: () => found,
    realisticClick: () => { clicks += 1; }
  };
  await assert.rejects(runDeposit({ screens: fakeScreens({ watch: "unavailable" }), dom }), (e) => e.message === "RECEIVE_UNAVAILABLE");
  const atRejection = clicks;
  await new Promise((r) => setTimeout(r, 800));
  assert.strictEqual(clicks, atRejection, "clicks landed after the call had failed");
});

test("a halt during the entry wait stops the interstitial clicks inside it", async () => {
  // D.waitUntil runs find() on its own timer, outside the wrapper's checks, and this
  // find() dismisses interstitials: only realisticClick's own check stops the click.
  const understand = { click() {}, focus() {}, getAttribute: () => null, textContent: "" };
  let clicks = 0;
  const dom = {
    $: (sel) => (sel === '[data-testid="network-warning-step-understand"]' ? understand : null),
    waitUntil: (find, ms) => new Promise((resolve) => {
      const end = Date.now() + ms;
      (function poll() {
        let v;
        try { v = find(); } catch (e) { return; } // the real one lets it escape the timer
        if (v) return resolve(v);
        if (Date.now() >= end) return resolve(null);
        setTimeout(poll, 50);
      })();
    }),
    realisticClick: () => { clicks += 1; }
  };
  await assert.rejects(runDeposit({ screens: fakeScreens({ watch: "unavailable" }), dom }), (e) => e.message === "RECEIVE_UNAVAILABLE");
  const atRejection = clicks;
  await new Promise((r) => setTimeout(r, 400));
  assert.strictEqual(clicks, atRejection, "interstitial clicks landed after the halt");
});

test("an IDV block keeps its code even with a screen up", async () => {
  const screens = fakeScreens({ after: "unavailable" });
  await assert.rejects(runDeposit({ screens, idvCode: "IDV_PENDING" }), (e) => e.message === "IDV_PENDING");
  assert.strictEqual(screens.calls.confirm, 0);
});

test("a slow IDV preflight still wins over a screen that is already up", async () => {
  const screens = fakeScreens({ watch: "unavailable" });
  await assert.rejects(runDeposit({ screens, idvCode: "IDV_PENDING", idvDelayMs: 600 }), (e) => e.message === "IDV_PENDING");
});
