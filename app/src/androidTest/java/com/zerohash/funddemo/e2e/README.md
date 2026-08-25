# Fund SDK e2e — real gating backend (AUTH-3838)

Instrumentation e2e suite that boots the **real Android SDK** against the
**real gating backend**. Native counterpart of the AUTH-3630 mobile web suite
in `zerohash-sdk` (`src/apps/mobile` e2e) — same environment, same JWT mint
contract, same two test configurations.

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

| File                    | Role                                                                                                          |
| ----------------------- | ------------------------------------------------------------------------------------------------------------- |
| `FundGatingE2ETest.kt`  | The two specs (Auth-enabled, non-Auth/ENG-6631)                                                               |
| `FundSessionHarness.kt` | Session boot + `RecordingCallbacks` + UI Automator helpers (semantic anchors from the AUTH-3630 page objects) |
| `utils/Gating.kt`       | Gating JWT mint + hardcoded per-config platforms (mirrors the web suite's `utils/jwt.ts` + `utils/platforms.ts`) |
