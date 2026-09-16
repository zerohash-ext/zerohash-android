// The shared data-testid census in dom-helpers.js. Used to report which screen a
// venue was showing when an automation failed to recognise it (AUTH-4511).

import assert from "node:assert";
import { test } from "node:test";
import { loadDom } from "./withdraw-shim.mjs";

const { dom } = loadDom();
const { normalizeTestid, summarizeTestids } = dom;

// Real testids observed on Coinbase pages.
const WITH_ADDRESSES = [
  "recent-send-0x938802aAC7acbE5B1c81D2eb54B7aBe9CA8b6e1b",
  "recent-send-0x938802aAC7acbE5B1c81D2eb54B7aBe9CA8b6e1b-cell-pressable",
  "recent-send-0xD1d5EfbE4BbF4Fa987524C1aCF4b867e90dB2c49",
  "recent-send-rLHzPsX6oXkzU2qL12kHCH8G8cnZv1rBJh",
  "favorite-9LFUw6mhsYEuuVDhJVkPbBZX1zn74KVACEqwMZhZUGd2",
  "favorite-9LFUw6mhsYEuuVDhJVkPbBZX1zn74KVACEqwMZhZUGd2-cell-pressable",
];

const PRIORITY = /^(?:step-|modal|two-factor|risk)/;

test("hex, XRP and Solana addresses all collapse", () => {
  assert.equal(normalizeTestid(WITH_ADDRESSES[0]), "recent-send-:id");
  assert.equal(normalizeTestid(WITH_ADDRESSES[3]), "recent-send-:id");
  assert.equal(normalizeTestid(WITH_ADDRESSES[4]), "favorite-:id");
});

test("no captured address survives normalization", () => {
  for (const id of WITH_ADDRESSES) {
    const out = normalizeTestid(id);
    assert.ok(!/0x[a-fA-F0-9]{6,}/.test(out), `hex leaked: ${out}`);
    assert.ok(!/[a-km-zA-HJ-NP-Z1-9]{26,}/.test(out), `base58 leaked: ${out}`);
  }
});

// A length-only id rule would eat camelCase step names, the most useful field.
test("camelCase step names are not mistaken for ids", () => {
  assert.equal(normalizeTestid("step-transactionDetailsStep-active"), "step-transactionDetailsStep-active");
  assert.equal(normalizeTestid("step-riskSelfServeStep-active"), "step-riskSelfServeStep-active");
});

test("known anchors pass through untouched", () => {
  for (const id of [
    "modal-overlay",
    "two-factor-button-SMS",
    "code-inputs-container",
    "send-success-content",
    "identity-access-view-wrapper",
    "policy-restriction-enforcer-v2",
    "scam-warning-intro",
  ]) {
    assert.equal(normalizeTestid(id), id);
  }
});

test("the -cell-pressable twin collapses onto its base", () => {
  assert.equal(normalizeTestid("l2-list-item-base-cell-pressable"), normalizeTestid("l2-list-item-base"));
});

test("asset tickers are kept", () => {
  assert.equal(normalizeTestid("send-asset-selector-cell-ETH-cell-pressable"), "send-asset-selector-cell-ETH");
});

// A `]` would close the start-failed wrapper group early.
test("square brackets are stripped", () => {
  assert.equal(normalizeTestid("weird-[bracketed]-testid"), "weird-bracketed-testid");
  assert.ok(!summarizeTestids(["a-]-b", "c-[-d"], PRIORITY).list.includes("]"));
});

test("total counts nodes, unique counts after normalization", () => {
  const c = summarizeTestids(WITH_ADDRESSES, PRIORITY);
  assert.equal(c.total, 6);
  assert.equal(c.unique, 2);
});

// Separates "never hydrated" from "rendered and our selectors missed".
test("an empty page reports zero", () => {
  const c = summarizeTestids([], PRIORITY);
  assert.equal(c.total, 0);
  assert.equal(c.list, "");
});

test("priority families sort ahead of page furniture", () => {
  const order = summarizeTestids(
    ["AppSwitcherButton", "coinbase-logo", "step-loaded-active", "modal-overlay"],
    PRIORITY
  ).list.split(",");
  assert.ok(order.indexOf("step-loaded-active") < order.indexOf("AppSwitcherButton"));
  assert.ok(order.indexOf("modal-overlay") < order.indexOf("coinbase-logo"));
});

// The anchor is added last, so only the priority sort keeps it in frame.
test("the cap cannot drop a priority family", () => {
  const noise = Array.from({ length: 60 }, (_, i) => `filler-item-${i}`);
  const c = summarizeTestids([...noise, "step-riskSelfServeStep-active"], PRIORITY);
  assert.ok(c.list.startsWith("step-riskSelfServeStep-active"));
  assert.equal(c.unique, 61);
});

test("overflow is reported, not silently truncated", () => {
  const c = summarizeTestids(Array.from({ length: 57 }, (_, i) => `filler-${i}`), PRIORITY);
  assert.equal(c.list.split(",").length, 41);
  assert.ok(c.list.endsWith(",+17"));
});

test("a testid named 'constructor' is not swallowed by Object.prototype", () => {
  assert.equal(summarizeTestids(["constructor", "toString", "modal-overlay"], PRIORITY).unique, 3);
});

test("collectTestids reads the attribute off every node", () => {
  const { dom: d } = loadDom();
  assert.deepStrictEqual([...d.collectTestids()], []);
  assert.equal(typeof d.testidCensus(PRIORITY).list, "string");
});
