// AUTH-4657: every page action and wait goes through the consumer's primitives
// block, the only place that calls the screen guard. A direct call elsewhere
// would keep running after a Coinbase screen halted the flow.
import assert from "node:assert";
import { existsSync, readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const ROOT = [
  "../../../zerohashsdk/src/main/assets/automation/",
  "../../../connectsdk/src/main/assets/automation/",
  "../../../Sources/ZerohashSDK/Platforms/Coinbase/",
  "../../../Sources/ConnectSDK/Platforms/Coinbase/"
]
  .map((rel) => fileURLToPath(new URL(rel, import.meta.url)))
  .find((dir) => existsSync(dir + "withdraw.js"));

const START = "// ── primitives (AUTH-4657) ──";
const END = "// ── end primitives ──";
const FORBIDDEN = [
  [/\bD\.sleep\b/, "sleep()"],
  [/\bD\.realisticClick\b/, "humanClick() or realisticClick()"],
  [/\bD\.waitFor\b/, "waitForElement() or waitFor()"],
  [/\bD\.waitUntil\b/, "waitUntil()"],
  [/\bD\.setReactValue\b/, "setReactValue()"],
  [/\.click\(\s*\)/, "plainClick()"],
  [/\.dispatchEvent\(/, "a primitive (dispatchPaste(), setInputValue())"]
];

function outsidePrimitives(src, file) {
  const a = src.indexOf(START);
  const b = src.indexOf(END);
  assert.ok(a >= 0 && b > a, `${file}: primitives block markers missing`);
  assert.strictEqual(src.indexOf(START, a + 1), -1, `${file}: more than one primitives block`);
  return src.slice(0, a) + src.slice(b + END.length);
}

const stripComments = (code) => code.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:\\])\/\/.*$/gm, "$1");

for (const file of ["withdraw.js", "get-deposit-address.js"]) {
  test(`${file}: page actions and waits go through the primitives block`, () => {
    const code = stripComments(outsidePrimitives(readFileSync(ROOT + file, "utf8"), file));
    for (const [re, use] of FORBIDDEN) {
      const m = re.exec(code);
      assert.ok(!m, `${file} calls ${m && m[0]} outside the primitives block; use ${use}`);
    }
  });
}
