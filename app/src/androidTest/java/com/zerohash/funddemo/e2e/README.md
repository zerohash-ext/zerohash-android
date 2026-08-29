# Native SDK e2e — real gating backend (AUTH-3838)

Instrumentation e2e suites that boot the **real Android SDK** against the
**real gating backend**. Native counterpart of the AUTH-3630 mobile web suite
in `zerohash-sdk` (`src/apps/mobile` e2e) — same environment, same JWT mint
contract, same test configurations.

Two suites live here:

| Suite                             | Flow               | Needs gating provisioning?                             |
| --------------------------------- | ------------------ | ------------------------------------------------------ |
| `FundGatingE2ETest`               | Fund               | Yes — two provisioned platforms                        |
| `FundWithdrawalsGatingE2ETest`    | Fund Withdrawals   | Yes — one provisioned platform, resolved at mint time  |

## What it exercises

```
FundGatingE2ETest
  └─ FundSessionHarness.boot()
       └─ ZerohashSDK.configureFund(env = Environment.GATING)
            └─ WebViewActivity
                 └─ https://connect-sdk.gating.0hash.com/mobile/#fund
                      └─ fund-iframe (real gating APIs, no mocks)
```

No mocks anywhere in the path. The JWT is minted live from the gating
kyc-mock-platform-server (`https://kyc-mock-platform-server.gating.0hash.com/manager/jwt`).

## The two configurations

Each test uses a dedicated gating platform provisioned for that configuration
(mirrors the web suite's hardcoded gating platforms):

| Test                                                    | Platform / participant | Provisioning                                                                          | Expectation                                                                                                              |
| ------------------------------------------------------- | ---------------------- | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `authEnabledJwt_rendersIntegrationsSourcePicker`        | `BM3LDA` / `62LHRQ`    | `fwc` + `crypto-deposits` + `auth_policy_enabled`; integrations cbase/gemini/robinhood/gemini-fake | Integrations source picker (or its "Deposit manually" escape hatch) renders; no SDK errors                               |
| `nonAuthJwt_bootsStraightToNativeFundFlow_eng6631Guard` | `HSBCRW` / `JLXERM`    | `fwc` only (no auth policy)                                                             | Native Fund flow ("Select asset") renders; **no** picker, no errors — guards the ENG-6631 regression at the native layer |

## Why UI Automator (not Espresso-Web)

The Fund UI renders inside a cross-origin iframe (`fund-iframe`) inside the
SDK's WebView. UI Automator asserts on the **accessibility tree**, which spans
all frames; Espresso-Web's Atom API cannot reach into the subframe.

Since we can't sniff WebView network traffic from instrumentation, assertions
are UI + native-callback based: `RecordingCallbacks` captures every
`onError` / `onEvent` / `onCompleted` / `onClose` the SDK fires.

## Credentials

The gating platform codes above are **hardcoded** in `utils/Gating.kt`
(`Gating.AUTH_ENABLED` / `Gating.NON_AUTH`) — same policy as the AUTH-3630 web
suite: they are internal gating-env test codes, not secrets. Note gating
**rejects** the dev codes (`H552SV` / `ZHH1NA`) with
`PermissionDenied: missing required relationship OPERATES_PLATFORM_FOR`.

## Fund Withdrawals suite

`FundWithdrawalsGatingE2ETest` covers the pre-linked withdrawal rail — the
destination comes from the JWT's `payload.withdrawal_details.external_account_id`,
so there is no in-flow picker. Native counterpart of what zerohash-ios shipped in
AUTH-2380.

The happy path is runnable: the gating withdrawal participant already has an
approved external account.

| Spec                                          | Needs network? | What it pins                                                           |
| --------------------------------------------- | -------------- | ---------------------------------------------------------------------- |
| `provisionedJwt_bootsIntoWithdrawalFlow`      | Yes            | A freshly minted JWT presents and the flow loads without SDK errors    |
| `mintResolvesAnApprovedExternalAccount`       | Yes            | The mint chain still finds an approved account (fails loudly if not)   |
| `malformedJwt_isRejectedAndFlowDoesNotStart`  | No             | `present()` returns null, one `ConfigurationError`, no WebView         |
| `expiredJwt_isRejectedAndFlowDoesNotStart`    | No             | Same via the `exp` branch — the failure a stale minted token produces  |
| `algNoneJwt_isRejectedAndFlowDoesNotStart`    | No             | An unsigned token never reaches the web app                            |
| `cancelAfterPresent_firesOnCloseOnce`         | No¹            | `cancel()` → `onClose` exactly once, even called twice                 |

¹ Boots a WebView so it will try the network, but the assertions are purely native.

**Why the JWT is minted in-test.** The manager validates `external_account_id`
against real data — a made-up id is rejected with `account not found` — and minted
tokens live ~5 minutes. So the id cannot be hardcoded (it goes stale) and the token
cannot be pasted (it expires). `Gating.mintFundWithdrawalsJwt()` therefore does
what the web suite's `getExternalAccountId` does:

1. mint a lookup token (`crypto-account-link` + `crypto-withdrawals`)
2. `GET kong-api/payments/external_accounts?participants=…&account_status=approved`
3. mint the real token with `withdrawal_details` pointing at the account it found

Note the platform differs from Fund's: `Gating.FUND_WITHDRAWALS` is `POZ6HT` /
`CC3OQA`. Minting `withdrawal_details` against the Fund platforms fails with
`account not found`, because the external account lives on this participant.

If the happy path starts failing with "No approved external account", the account
was unlinked or expired — link and approve one on `CC3OQA` again; it is not a code
problem.

## Running locally

1. Start an emulator (API 34, Google APIs) or connect a device with network
   access.
2. `./gradlew :app:connectedDebugAndroidTest`
3. Reports land in `app/build/reports/androidTests/connected/`.

## CI

`.github/workflows/fund-e2e-gating.yml` runs the suite on an emulator (KVM,
AVD snapshot cache) on PRs/pushes to `main` — no secrets needed. The workflow
file is filtered out of the public `zerohash-ext` mirror by the sync workflow,
so it stays private.

## File map

| File                                    | Role                                                                                                            |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `FundGatingE2ETest.kt`                  | The two Fund specs (Auth-enabled, non-Auth/ENG-6631)                                                            |
| `FundSessionHarness.kt`                 | Fund session boot + `RecordingCallbacks` + UI Automator helpers (semantic anchors from the AUTH-3630 page objects) |
| `FundWithdrawalsGatingE2ETest.kt`       | Fund Withdrawals specs — happy path, JWT gate, lifecycle (see above)                                            |
| `FundWithdrawalsSessionHarness.kt`      | Fund Withdrawals session boot + `RecordingFundWithdrawalsCallbacks`                                             |
| `utils/Gating.kt`                       | Gating JWT mint + hardcoded per-config platforms (mirrors the web suite's `utils/jwt.ts` + `utils/platforms.ts`)  |
| `utils/TestJwt.kt`                      | Locally-built JWTs for the client-side-gate specs — no network, no provisioning                                  |

Both harnesses live in the same package, so their recording callbacks are named
distinctly (`RecordingCallbacks` for Fund, `RecordingFundWithdrawalsCallbacks` for
Fund Withdrawals) — the two SDK callback interfaces differ, so one cannot serve both.
