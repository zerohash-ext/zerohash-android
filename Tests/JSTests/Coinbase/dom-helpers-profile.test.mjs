import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const HELPER_CANDIDATES = [
  "../../../zerohashsdk/src/main/assets/automation/shared-dom-helpers.js",
  "../../../connectsdk/src/main/assets/automation/shared-dom-helpers.js",
  "../../../Sources/ZerohashSDK/AutomationScripts/shared-dom-helpers.js",
  "../../../Sources/ConnectSDK/AutomationScripts/shared-dom-helpers.js"
];
const TIMEOUT_MS = 20;
const GENEROUS_TIMEOUT_MS = 1000;
const TEST_URL = "https://x.test";
const POST_REQUEST = { method: "POST" };

function helpersPath() {
  const paths = HELPER_CANDIDATES.map((relative) => fileURLToPath(new URL(relative, import.meta.url)));
  return paths.find((path) => existsSync(path));
}

function loadHelpers(globals) {
  const window = {};
  const context = vm.createContext(Object.assign({ window, setTimeout, clearTimeout, console }, globals));
  vm.runInContext(readFileSync(helpersPath(), "utf8"), context);
  return window.__zhDom;
}

function abortableHang(_url, init) {
  return new Promise((_resolve, reject) => {
    init.signal.addEventListener("abort", () => {
      const aborted = new Error("aborted");
      aborted.name = "AbortError";
      reject(aborted);
    });
  });
}

test("trimmedText trims strings and returns null for blanks and non-strings", () => {
  const helpers = loadHelpers({});

  assert.equal(helpers.trimmedText("  Jane Mary "), "Jane Mary");
  assert.equal(helpers.trimmedText("   "), null);
  assert.equal(helpers.trimmedText(42), null);
  assert.equal(helpers.trimmedText(undefined), null);
});

test("parseJsonOrNull parses JSON and returns null otherwise", () => {
  const helpers = loadHelpers({});

  assert.equal(helpers.parseJsonOrNull("{\"a\":1}").a, 1);
  assert.equal(helpers.parseJsonOrNull("<html>"), null);
});

test("fetchTextWithTimeout returns status and text and adds an abort signal", async () => {
  const calls = [];

  function fetch(url, init) {
    calls.push({ url, init });
    return Promise.resolve({ status: 200, text: () => Promise.resolve("ok") });
  }

  const helpers = loadHelpers({ fetch, AbortController });

  const response = await helpers.fetchTextWithTimeout(TEST_URL, POST_REQUEST, GENEROUS_TIMEOUT_MS);

  assert.equal(response.status, 200);
  assert.equal(response.text, "ok");
  assert.equal(calls[0].url, TEST_URL);
  assert.equal(calls[0].init.method, POST_REQUEST.method);
  assert.ok(calls[0].init.signal);
});

test("fetchTextWithTimeout rejects with AbortError after the timeout", async () => {
  const helpers = loadHelpers({ fetch: abortableHang, AbortController });

  const pending = helpers.fetchTextWithTimeout(TEST_URL, {}, TIMEOUT_MS);

  await assert.rejects(pending, { name: "AbortError" });
});
