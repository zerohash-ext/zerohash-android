// failureContext: the diagnostic attached when a withdraw step fails (AUTH-4511).

import assert from "node:assert";
import { test } from "node:test";
import { loadWithdraw } from "./withdraw-shim.mjs";

const { failureContext } = loadWithdraw().internals;

// Called from a catch, so a throw here would replace the real error.
test("it leads with the phase and never throws", () => {
  const ctx = failureContext("detect-2fa");
  assert.ok(ctx.startsWith("step=detect-2fa"), ctx);
  assert.ok(!ctx.includes("]"), "a ] would break the SDK's start-failed unwrap");
});

test("it reports the page counts", () => {
  const ctx = failureContext("detect-2fa");
  for (const field of ["url=", "activeStep=", "modal=", "total=", "unique=", "ids="]) {
    assert.ok(ctx.includes(field), `${field} missing from: ${ctx}`);
  }
});

// The wrapper is parsed downstream; breaking it degrades the user's message.
test("the wrapped message still yields its inner code to the SDK", () => {
  const wrapped = `withdraw/start-failed [${failureContext("detect-2fa")}]: withdraw/unknown-failure: neither success nor 2FA UI appeared`;
  const match = /^withdraw\/start-failed \[[^\]]*\]: ([a-z][\w-]*[-_/:][\w-]+)/i.exec(wrapped);
  assert.ok(match, "the SDK's unwrap pattern must still match");
  assert.equal(match[1], "withdraw/unknown-failure");
});
