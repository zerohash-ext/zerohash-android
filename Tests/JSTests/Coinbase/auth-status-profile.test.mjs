import assert from "node:assert/strict";
import { test } from "node:test";
import {
  PROFILE_QUERY, PROFILE_URL, FICTIONAL_USER,
  graphqlError, hangs, httpError, networkFailure, rawText, runAuthStatus, userQuery
} from "./auth-status-shim.mjs";

const THREE_TIMEOUTS_PLUS_BACKOFF_MS = 13_000;
const JITTER_CEILING_MS = 20_000;
const PROFILE_ATTEMPT_TIMEOUT_MS = 4_000;
const ONE_ATTEMPT_BUDGET_MS = 5_000;
const SHRUNK_LAST_ATTEMPT_BUDGET_MS = 7_000;
const AMPLE_BUDGET_MS = 60_000;
const SCHEDULING_SLACK_MS = 1_000;
const SIGNED_IN = { testids: ["ProfileDropdownAvatar-wrapper"] };
const PROFILE = { userId: FICTIONAL_USER.uuid, firstName: FICTIONAL_USER.firstName, lastName: FICTIONAL_USER.lastName };
const named = (events, name) => events.filter((e) => e.event_name === name);

test("signed in: returns exactly userId, firstName, lastName", async () => {
  const { result, calls } = await runAuthStatus({ ...SIGNED_IN, responses: [userQuery()] });
  assert.equal(result.loggedIn, true);
  assert.deepEqual(result.profile, PROFILE);
  assert.equal(result.profileFailure, undefined);
  assert.equal(calls.length, 1);
});

test("the query is plain text for exactly the three fields, same-origin with cookies", async () => {
  const { calls: [c] } = await runAuthStatus({ ...SIGNED_IN, responses: [userQuery()] });
  assert.equal(c.url, PROFILE_URL);
  assert.equal(c.method, "POST");
  assert.equal(c.credentials, "include");
  assert.equal(c.cache, "no-store");
  assert.equal(c.headers.accept, "application/json");
  assert.equal(c.headers["content-type"], "application/json");
  assert.equal(c.headers["x-apollo-operation-name"], "userQuery");
  assert.deepEqual(c.body, { operationName: "userQuery", query: PROFILE_QUERY, variables: {} });
});

test("only the three Compliance-approved fields reach the profile", async () => {
  const { result } = await runAuthStatus({
    ...SIGNED_IN,
    responses: [userQuery({ extraProps: { dateOfBirth: "1990-01-01", name: "Jane" } })]
  });
  assert.deepEqual(Object.keys(result.profile).sort(), ["firstName", "lastName", "userId"]);
});

test("values are trimmed and otherwise passed through as-is", async () => {
  const { result } = await runAuthStatus({
    ...SIGNED_IN,
    responses: [userQuery({ firstName: "  jane MARY ", lastName: " de la Cruz\n" })]
  });
  assert.equal(result.profile.firstName, "jane MARY");
  assert.equal(result.profile.lastName, "de la Cruz");
});

test("the 4s dashboard-host fallback still reads the profile", async () => {
  const { result, calls } = await runAuthStatus({ responses: [userQuery()] });
  assert.equal(result.loggedIn, true);
  assert.deepEqual(result.profile, PROFILE);
  assert.equal(calls.length, 1);
});

test("signed out on the login host: no profile, no query", async () => {
  const { result, calls } = await runAuthStatus({ href: "https://login.coinbase.com/signin" });
  assert.deepEqual(result, { loggedIn: false });
  assert.equal(calls.length, 0);
});

test("signed-out CTA on www: no profile, no query", async () => {
  const { result, calls } = await runAuthStatus({ testids: ["header-signin-link"] });
  assert.deepEqual(result, { loggedIn: false });
  assert.equal(calls.length, 0);
});

for (const faults of [1, 2]) {
  test(`${faults} transient failure(s) then success returns the profile`, async () => {
    const responses = [...Array(faults).fill(httpError(503)), userQuery()];
    const { result, calls, events } = await runAuthStatus({ ...SIGNED_IN, responses });
    assert.deepEqual(result.profile, PROFILE);
    assert.equal(calls.length, faults + 1);
    const attempts = named(events, "auth_profile_attempt");
    assert.deepEqual(attempts.map((a) => a.outcome), [...Array(faults).fill("http_error"), "ok"]);
    const [res] = named(events, "auth_profile_result");
    assert.equal(res.outcome, "ok");
    assert.equal(res.attempts, faults + 1);
    assert.equal("error" in res, false);
  });
}

const INDETERMINATE = [
  ["http 500", "http_error", httpError(500)],
  ["network failure", "http_error", networkFailure()],
  ["graphql error", "graphql_error", graphqlError({ code: "INTERNAL_SERVER_ERROR" })],
  ["non-JSON body", "invalid_response", rawText("<html>not json</html>")],
  ["no userProperties", "invalid_response", { status: 200, body: { data: { viewer: null } } }]
];
for (const [label, reason, response] of INDETERMINATE) {
  test(`${label}: three attempts, then PROFILE_INDETERMINATE/${reason}`, async () => {
    const { result, calls, events } = await runAuthStatus({ ...SIGNED_IN, responses: [response] });
    assert.equal(result.loggedIn, true);
    assert.equal(result.profile, undefined);
    assert.deepEqual(result.profileFailure, { error: "PROFILE_INDETERMINATE", reason });
    assert.equal(calls.length, 3);
    assert.equal(named(events, "auth_profile_attempt").length, 3);
    const [res] = named(events, "auth_profile_result");
    assert.deepEqual(
      { outcome: res.outcome, attempts: res.attempts, error: res.error },
      { outcome: reason, attempts: 3, error: "PROFILE_INDETERMINATE" }
    );
  });
}

for (const [label, response] of [
  ["no legal name", userQuery({ legalName: null })],
  ["blank last name", userQuery({ lastName: "   " })],
  ["no uuid", userQuery({ uuid: null })]
]) {
  test(`${label}: PROFILE_INCOMPLETE at once, no retry`, async () => {
    const { result, calls, events } = await runAuthStatus({ ...SIGNED_IN, responses: [response] });
    assert.deepEqual(result.profileFailure, { error: "PROFILE_INCOMPLETE", reason: "missing_field" });
    assert.equal(calls.length, 1);
    assert.equal(named(events, "auth_profile_result")[0].error, "PROFILE_INCOMPLETE");
  });
}

test("three timeouts: PROFILE_INDETERMINATE/timeout inside the ~13.5s budget", async () => {
  const { result, calls, events } = await runAuthStatus({ ...SIGNED_IN, responses: [hangs()] });
  assert.deepEqual(result.profileFailure, { error: "PROFILE_INDETERMINATE", reason: "timeout" });
  assert.equal(calls.length, 3);
  const [res] = named(events, "auth_profile_result");
  assert.ok(res.total_ms >= THREE_TIMEOUTS_PLUS_BACKOFF_MS, `total_ms=${res.total_ms}`);
  assert.ok(res.total_ms < JITTER_CEILING_MS, `total_ms=${res.total_ms}`);
});

test("a deadline already passed: no query, PROFILE_INDETERMINATE/timeout after zero attempts", async () => {
  const { result, calls, events } = await runAuthStatus({ ...SIGNED_IN, responses: [userQuery()], profileBudgetMs: 0 });
  assert.equal(calls.length, 0);
  assert.equal(result.loggedIn, true);
  assert.equal(result.profile, undefined);
  assert.deepEqual(result.profileFailure, { error: "PROFILE_INDETERMINATE", reason: "timeout" });
  assert.deepEqual(result.profileDiag.attempts, []);
  assert.equal(result.profileDiag.result.attempts, 0);
  assert.equal(named(events, "auth_profile_attempt").length, 0);
  assert.equal(named(events, "auth_profile_result")[0].attempts, 0);
});

test("room for one attempt only: a single query, then PROFILE_INDETERMINATE/timeout before the deadline", async () => {
  const { result, calls, elapsedMs } = await runAuthStatus({
    ...SIGNED_IN,
    responses: [hangs()],
    profileBudgetMs: ONE_ATTEMPT_BUDGET_MS
  });
  assert.equal(calls.length, 1);
  assert.deepEqual(result.profileFailure, { error: "PROFILE_INDETERMINATE", reason: "timeout" });
  assert.equal(result.profileDiag.result.attempts, 1);
  assert.ok(elapsedMs < ONE_ATTEMPT_BUDGET_MS, `elapsedMs=${elapsedMs}`);
});

test("the last attempt shrinks to the time left before the deadline", async () => {
  const { result, calls, elapsedMs } = await runAuthStatus({
    ...SIGNED_IN,
    responses: [hangs()],
    profileBudgetMs: SHRUNK_LAST_ATTEMPT_BUDGET_MS
  });
  assert.equal(calls.length, 2);
  assert.deepEqual(result.profileFailure, { error: "PROFILE_INDETERMINATE", reason: "timeout" });
  const [, last] = result.profileDiag.attempts;
  assert.ok(last.latency_ms < PROFILE_ATTEMPT_TIMEOUT_MS, `latency_ms=${last.latency_ms}`);
  assert.ok(elapsedMs < SHRUNK_LAST_ATTEMPT_BUDGET_MS + SCHEDULING_SLACK_MS, `elapsedMs=${elapsedMs}`);
});

test("a deadline with ample room still returns the profile after a retry", async () => {
  const { result, calls } = await runAuthStatus({
    ...SIGNED_IN,
    responses: [httpError(503), userQuery()],
    profileBudgetMs: AMPLE_BUDGET_MS
  });
  assert.deepEqual(result.profile, PROFILE);
  assert.equal(result.profileFailure, undefined);
  assert.equal(calls.length, 2);
});

test("attempt rows carry only codes and numbers", async () => {
  const { events } = await runAuthStatus({ ...SIGNED_IN, responses: [httpError(503), userQuery()] });
  const [first] = named(events, "auth_profile_attempt");
  assert.deepEqual(
    Object.keys(first).sort(),
    ["attempt", "event_name", "http_status", "latency_ms", "outcome"]
  );
  assert.equal(first.attempt, 1);
  assert.equal(first.http_status, 503);
  assert.equal(typeof first.latency_ms, "number");
});

test("no profile value reaches telemetry or profileDiag", async () => {
  const { result, events } = await runAuthStatus({ ...SIGNED_IN, responses: [userQuery()] });
  const leaked = JSON.stringify([events, result.profileDiag]);
  for (const v of [FICTIONAL_USER.uuid, "Jane", "Mary", "Doe"]) {
    assert.equal(leaked.includes(v), false, v);
  }
});

test("a broken telemetry sink never fails the status", async () => {
  const { result } = await runAuthStatus({ ...SIGNED_IN, responses: [userQuery()], telemetryThrows: true });
  assert.deepEqual(result.profile, PROFILE);
});

test("a telemetry global whose getter throws never fails the status", async () => {
  const { result } = await runAuthStatus({ ...SIGNED_IN, responses: [userQuery()], telemetryGetterThrows: true });
  assert.deepEqual(result.profile, PROFILE);
});

test("no AbortController: every attempt is an http_error, then PROFILE_INDETERMINATE/http_error", async () => {
  const { result, calls, events } = await runAuthStatus({
    ...SIGNED_IN,
    responses: [userQuery()],
    noAbortController: true
  });
  assert.equal(calls.length, 0);
  assert.equal(result.profile, undefined);
  assert.deepEqual(result.profileFailure, { error: "PROFILE_INDETERMINATE", reason: "http_error" });
  const attempts = named(events, "auth_profile_attempt");
  assert.equal(attempts.length, 3);
  for (const attempt of attempts) {
    assert.equal(attempt.outcome, "http_error");
    assert.equal(attempt.http_status, null);
  }
  const results = named(events, "auth_profile_result");
  assert.equal(results.length, 1);
  assert.equal(results[0].attempts, 3);
});

test("a missing field alongside a GraphQL error is graphql_error and retried", async () => {
  const partial = {
    status: 200,
    body: {
      data: {
        viewer: {
          userProperties: {
            uuid: FICTIONAL_USER.uuid,
            personalDetails: { legalName: { firstName: "Jane Mary", lastName: null } }
          }
        }
      },
      errors: [{ message: "partial", extensions: { code: "DOWNSTREAM_SERVICE_ERROR" } }]
    }
  };
  const { result, calls, events } = await runAuthStatus({ ...SIGNED_IN, responses: [partial] });
  assert.deepEqual(result.profileFailure, { error: "PROFILE_INDETERMINATE", reason: "graphql_error" });
  assert.equal(calls.length, 3);
  assert.equal("graphql_code" in named(events, "auth_profile_attempt")[0], false);
});

test("no GraphQL error content ever leaves the script", async () => {
  const leaky = `${FICTIONAL_USER.firstName} ${FICTIONAL_USER.lastName} ${FICTIONAL_USER.uuid} jane@example.com`;
  const { result, events } = await runAuthStatus({
    ...SIGNED_IN,
    responses: [{
      status: 200,
      body: { data: null, errors: [{ message: leaky, extensions: { code: FICTIONAL_USER.lastName, detail: leaky } }] }
    }]
  });
  assert.deepEqual(result.profileFailure, { error: "PROFILE_INDETERMINATE", reason: "graphql_error" });
  const out = JSON.stringify([result, events]);
  for (const v of [FICTIONAL_USER.firstName, FICTIONAL_USER.lastName, FICTIONAL_USER.uuid, "jane@example.com"]) {
    assert.equal(out.includes(v), false, v);
  }
  for (const e of named(events, "auth_profile_attempt")) {
    assert.equal("graphql_code" in e, false);
  }
});

test("an empty errors array is not a GraphQL error", async () => {
  const { result } = await runAuthStatus({
    ...SIGNED_IN,
    responses: [{ status: 200, body: { data: { viewer: null }, errors: [] } }]
  });
  assert.deepEqual(result.profileFailure, { error: "PROFILE_INDETERMINATE", reason: "invalid_response" });
});

test("profile values are trimmed once, in the script", async () => {
  const { result } = await runAuthStatus({
    ...SIGNED_IN,
    responses: [userQuery({ uuid: ` ${FICTIONAL_USER.uuid} `, firstName: " Jane Mary ", lastName: "Doe " })]
  });
  assert.deepEqual(result.profile, { userId: FICTIONAL_USER.uuid, firstName: "Jane Mary", lastName: "Doe" });
});
