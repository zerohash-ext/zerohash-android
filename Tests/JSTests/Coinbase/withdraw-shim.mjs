// Loads withdraw.js in a Node VM with a mutable fake DOM, so the post-confirm
// decision logic can be tested without a browser.
//
// Two properties matter and are easy to lose:
//
//  1. The document is MUTABLE. `doc.present` is a live Set. A test can add or
//     remove selectors between polls, which is the only way to exercise
//     settlePostConfirm's real job: stay unsettled while Coinbase is still
//     rendering, then settle when a decisive screen mounts.
//  2. The clock is INJECTABLE. Pass `{ sleep }` to loadWithdraw to drive the poll
//     loop deterministically instead of burning wall-clock time.
//
// Selectors are matched by exact string equality. The shim never hardcodes a
// selector: it harvests SEL from withdraw.js itself, so the two cannot drift.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const SRC = fileURLToPath(
  new URL("../../../zerohashsdk/src/main/assets/automation/withdraw.js", import.meta.url)
);
// Read and keep the source at module scope, matching shim.mjs. loadWithdraw is
// called once per test; re-reading 1100 lines each time is pure waste.
const SOURCE = readFileSync(SRC, "utf8");
const DOM_HELPERS = readFileSync(
  fileURLToPath(new URL("../../../zerohashsdk/src/main/assets/automation/dom-helpers.js", import.meta.url)),
  "utf8"
);

// ECMAScript intrinsics come free inside a VM context. These are the host-provided
// globals withdraw.js touches at LOAD time, so they are all the sandbox needs.
// Deliberately absent: Event, DataTransfer, ClipboardEvent, HTMLInputElement.
//
const hostGlobals = () => ({
  setTimeout,
  clearTimeout,
  console,
  // A page realm always has this; failureContext reads location.pathname.
  location: { pathname: "/send", href: "https://www.coinbase.com/send" }
});

// window.__zhDom is injected separately in production (dom-helpers.js).
const domStub = (sleep) => ({
  sleep: sleep || (() => Promise.resolve()),
  waitFor: () => Promise.reject(new Error("withdraw-shim: waitFor is not stubbed")),
  realisticClick: () => {},
  findButtonByText: () => null
});

function run(document, { sleep } = {}) {
  // The real dom-helpers.js installs window.__zhDom, so the shared helpers
  // (testidCensus and friends) are exercised as shipped. Only the timing and
  // click parts are stubbed, so tests can drive the poll loop.
  const window = {};
  const sandbox = { window, document, ...hostGlobals() };
  vm.runInNewContext(DOM_HELPERS, sandbox);
  Object.assign(window.__zhDom, domStub(sleep));
  vm.runInNewContext(SOURCE, sandbox);
  if (!window.__zhWithdraw || !window.__zhWithdraw.__internals) {
    throw new Error("withdraw-shim: window.__zhWithdraw.__internals is missing");
  }
  // Guard SEL specifically. Without this, a missing SEL exports as `undefined` and
  // stays silent until the first DOM query — so a classifier-only test file passes
  // green while the harness is broken for every other file.
  if (!window.__zhWithdraw.__internals.SEL) {
    throw new Error("withdraw-shim: __internals.SEL is missing — the harness harvests selectors from it");
  }
  return window;
}

// A document that matches nothing — enough to load withdraw.js and read its
// constants back out. withdraw.js queries nothing at load time.
const inertDocument = () => ({ querySelector: () => null, querySelectorAll: () => [] });

// Harvested once, so no selector string is ever duplicated in this file. If
// withdraw.js renames or retypes a selector, every test here follows it.
export const SEL = run(inertDocument()).__zhWithdraw.__internals.SEL;

/**
 * A mutable fake DOM.
 *
 * Deliberate limitations:
 * - An element is either absent or visible. "Present but hidden" is not
 *   modellable.
 * - Elements are minted fresh per query, so node identity never holds and
 *   click() / focus() record nothing.
 * - `childCounts` is keyed by SELECTOR STRING for `present` entries, but by
 *   data-testid VALUE for `activeSteps` entries.
 *
 * @param {object}   opts
 * @param {string[]} opts.present     selectors that should match a visible element
 * @param {object}   opts.childCounts key -> element.children.length (default 0)
 * @param {string[]} opts.activeSteps data-testid values returned for SEL.STEP_ACTIVE
 */
export function makeDocument({ present = [], childCounts = {}, activeSteps = [] } = {}) {
  const element = (key) => ({
    // Not a DOM property. It records which selector produced this element, so a
    // test can assert WHICH of several candidate rows was chosen rather than
    // merely that something was.
    selector: key,
    // isVisible() short-circuits on offsetParent, so a non-null value suffices.
    // The rect is its second arm — kept so the stub honours isVisible's whole
    // contract rather than its current branch order.
    offsetParent: {},
    children: { length: childCounts[key] ?? 0 },
    getBoundingClientRect: () => ({ width: 10, height: 10 }),
    getAttribute: () => null,
    querySelector: () => null,
    querySelectorAll: () => [],
    // Every icon Coinbase renders sits inside the control it labels, so `closest`
    // resolving to a visible ancestor is a faithful model for the nav tabs and the
    // transfer drawer's items. It is NOT a general ancestor walk: it answers "yes,
    // there is one" for any selector, so a test must pin the icon by supplying its
    // exact `[data-icon-name="…"]` string in `present`.
    closest: () => element(key + " (ancestor)"),
    click() {},
    focus() {},
    textContent: "",
    innerText: ""
  });

  const stepElement = (testid) => ({
    ...element(testid),
    getAttribute: (name) => (name === "data-testid" ? testid : null)
  });

  return {
    // Live handles. Mutate between polls to make the page change under the loop.
    // Both are read through `this`, so REASSIGNING either (doc.present = new Set(…))
    // works as well as mutating it. Reading one through a closure and the other
    // through `this` would make reassignment silently no-op on one of them — a
    // green-for-the-wrong-reason trap.
    present: new Set(present),
    activeSteps: new Set(activeSteps),
    body: element("body"),
    querySelectorAll(sel) {
      if (sel === SEL.STEP_ACTIVE) return [...this.activeSteps].map(stepElement);
      return this.present.has(sel) ? [element(sel)] : [];
    },
    querySelector(sel) {
      if (sel === SEL.STEP_ACTIVE) {
        const first = [...this.activeSteps][0];
        return first === undefined ? null : stepElement(first);
      }
      return this.present.has(sel) ? element(sel) : null;
    }
  };
}

/**
 * Runs withdraw.js against a fresh mutable document.
 *
 * @param {object} documentOptions passed to makeDocument
 * @param {object} hooks           { sleep } — injected as window.__zhDom.sleep, so
 *                                 a test can count polls and mutate the document
 *                                 between them
 * @returns {{ internals, window, document }} `window` lets a test preset
 *          window.__zhWithdrawState; `document` exposes the live `present` and
 *          `activeSteps` Sets.
 */
export function loadWithdraw(documentOptions = {}, hooks = {}) {
  const document = makeDocument(documentOptions);
  const window = run(document, hooks);
  return { internals: window.__zhWithdraw.__internals, window, document };
}

/**
 * Re-homes an object created inside the VM sandbox into this realm. Required
 * before a strict assertion: the sandbox has its own Object.prototype, which
 * deepStrictEqual compares by reference. Shallow.
 */
export const rehome = (o) => ({ ...o });

/** The shared __zhDom helpers, against a document of your choosing. */
export function loadDom(documentOptions = {}) {
  const document = makeDocument(documentOptions);
  const window = {};
  vm.runInNewContext(DOM_HELPERS, { window, document, ...hostGlobals() });
  return { dom: window.__zhDom, document };
}
