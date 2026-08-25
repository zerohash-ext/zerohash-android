# zerohash-android

Kotlin SDK that embeds zerohash flows into a native Android app — **Fund**
(account funding / pay-to-settle) and **Crypto Withdrawals** (withdraw crypto to
an external address). It renders the zerohash mobile web app inside a hardened
`WebView` and bridges it to a typed Kotlin API.

| | |
| --- | --- |
| **Package** | `com.zerohash.sdk` |
| **Min SDK** | Android API 21 (Android 5.0+) |
| **Language** | Kotlin 1.9+ |
| **Build** | Gradle 8.2+, JDK 17 |

## Installation

The SDK is published to Maven Central as `com.zerohash:zerohash-android`.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.zerohash:zerohash-android:1.2.0")
}
```

## Quick start — Fund

```kotlin
import com.zerohash.sdk.ZerohashSDK
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.Environment
import com.zerohash.sdk.GenericEvent
import com.zerohash.sdk.Theme
import com.zerohash.sdk.fund.FundCallbacks
import com.zerohash.sdk.fund.FundCompletedEvent
import com.zerohash.sdk.fund.ZerohashFundSession

class MainActivity : AppCompatActivity() {

    private var fundSession: ZerohashFundSession? = null

    private fun openFund(jwt: String) {
        fundSession = ZerohashSDK.configureFund(
            jwt = jwt,                              // required
            environment = Environment.PRODUCTION,   // optional; or Environment.SANDBOX
            theme = Theme.SYSTEM,                    // optional (default)
            callbacks = object : FundCallbacks {
                override fun onClose() { fundSession = null }
                override fun onError(error: ZerohashError) { /* show error */ }
                override fun onEvent(event: GenericEvent) { /* analytics */ }
                override fun onLoaded() { /* WebView content is ready */ }
                override fun onCompleted(event: FundCompletedEvent) {
                    // event.transactionId, event.assetSymbol, event.amount,
                    // event.depositAddress, event.network, event.fundId,
                    // event.notionalAmount
                }
                override fun onFailed(event: FundCompletedEvent) {
                }
            }
        )
        fundSession?.present(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        fundSession?.cancel()
    }
}
```

Only `jwt` and `callbacks` are required. `environment` defaults to `PRODUCTION`
(set it to `Environment.SANDBOX` to point at the test backend, see
[Environment](#environment) below), `theme` to `SYSTEM` (mapped to the web app's
`auto`).

## Environment

`Environment` selects which zerohash backend the flow runs against. Both
`configureFund` and `configureCryptoWithdrawals` take it as an optional parameter.

| Value | Backend host | Use for |
| --- | --- | --- |
| `Environment.PRODUCTION` (default) | `sdk-cdn.zerohash.com` | Live partner traffic |
| `Environment.SANDBOX` | `sdk-cdn.cert.zerohash.com` | Integration and testing |

```kotlin
fundSession = ZerohashSDK.configureFund(
    jwt = jwt,
    environment = Environment.SANDBOX,
    callbacks = callbacks,
)
```

The JWT and the environment must match: a sandbox-minted JWT only works with
`Environment.SANDBOX`, and a production JWT only with `Environment.PRODUCTION`.

## Quick start — Crypto Withdrawals

Same shape as Fund: configure a session, present it from an `Activity`, and
cancel it when the host is destroyed.

```kotlin
import com.zerohash.sdk.ZerohashSDK
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.Environment
import com.zerohash.sdk.GenericEvent
import com.zerohash.sdk.Theme
import com.zerohash.sdk.cryptowithdrawals.CryptoWithdrawalsCallbacks
import com.zerohash.sdk.cryptowithdrawals.CryptoWithdrawalsCompletedEvent
import com.zerohash.sdk.cryptowithdrawals.ZerohashCryptoWithdrawalsSession

class MainActivity : AppCompatActivity() {

    private var withdrawalsSession: ZerohashCryptoWithdrawalsSession? = null

    private fun openCryptoWithdrawals(jwt: String) {
        withdrawalsSession = ZerohashSDK.configureCryptoWithdrawals(
            jwt = jwt,                              // required
            environment = Environment.PRODUCTION,   // optional; or Environment.SANDBOX
            theme = Theme.SYSTEM,                   // optional (default)
            callbacks = object : CryptoWithdrawalsCallbacks {
                override fun onClose() { withdrawalsSession = null }
                override fun onError(error: ZerohashError) { /* show error */ }
                override fun onEvent(event: GenericEvent) { /* analytics */ }
                override fun onLoaded() { /* WebView content is ready */ }
                override fun onCompleted(event: CryptoWithdrawalsCompletedEvent) {
                    // event.withdrawalRequestId, event.status,
                    // event.statusDetails, event.assetId, event.networkId,
                    // event.amount, event.rawData
                }
                override fun onFailed(event: CryptoWithdrawalsCompletedEvent) {
                }
            }
        )
        withdrawalsSession?.present(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        withdrawalsSession?.cancel()
    }
}
```

The JWT must carry the permissions for the flow you present — Fund and Crypto
Withdrawals are minted with different permission sets.

## Callbacks

Names match the zerohash web SDK, so the same handler names apply whether you
integrate on web, Android or iOS — the flow is identified by the session you
configure, not by the callback name. Both flows share the core set; `onDeposit` exists on Fund only,
matching the web SDK.

| Callback | When it fires |
| --- | --- |
| `onCompleted(event)` | The transaction succeeded. `event` is `FundCompletedEvent` or `CryptoWithdrawalsCompletedEvent` |
| `onFailed(event)` | The transaction reached a terminal **failed** state. Same event type as `onCompleted` — which callback fired tells you the outcome |
| `onError(error)` | An SDK or request error (network, auth, validation, config) |
| `onLoaded()` | The flow's WebView content is ready (the web component mounted). Earlier than the web SDK's `onLoaded` — see the note below |
| `onDeposit(event)` | **Fund only.** Status of a deposit funded from an external source. **Not terminal** — see below |
| `onEvent(event)` | Lifecycle/analytics events, with the original identifier on `event.type` |
| `onClose()` | The user closed the flow, or `cancel()` was called |

`onLoaded` fires when the WebView content is ready, which is **earlier** than the
web SDK's `onLoaded` (that one fires once the flow has booted and rendered). The
web layer's own ready signal is not currently forwarded over the bridge, so do not
treat this as "the user can see the flow" — dismissing a loading spinner here can
uncover a still-blank WebView.

Fund reports a deposit two ways, depending on how the money arrived — matching the
web SDK exactly.

A **manual or Pay** deposit is terminal, and reaches `onCompleted`/`onFailed` with
all seven `FundCompletedEvent` fields (`transactionId`, `fundId`, `assetSymbol`,
`amount`, `depositAddress`, `network`, `notionalAmount`).

A deposit funded from an **external source** (the "connect an account" path) reaches
`onDeposit` **only**, with a `FundDepositEvent`. That callback is a *status*, not an
outcome: it also fires while account matching is verifying, and can arrive more than
once for the same deposit. Read the outcome off `event.status` (`PROCESSED`,
`FAILED`, `PENDING`) or the derived `event.success`, and use
`event.accountMatchingStatus` / `event.accountMatchingReason` for a name-mismatch
failure — that reason is the only explanation available anywhere in the stack. Do
not treat the call itself as completion.

A failed transaction is a flow outcome, **not** an error. Implement both if you
need to cover every unsuccessful path. `onFailed`, `onLoaded` and `onDeposit` have no-op
defaults, so override them only if you use them.

The two flows differ in one respect. A failed **deposit** (Fund) invokes
`onFailed` only. A failed **crypto withdrawal** invokes `onFailed` *and*
`onError`, for backwards compatibility with hosts written before `onFailed`
existed — `onError` was that flow's only failure signal. Build against `onFailed`
in both cases; if you implement both callbacks, guard against counting a failed
withdrawal twice. The compatibility `onError` is deprecated and will be removed in
a future major version.

## Session API

Both `ZerohashFundSession` and `ZerohashCryptoWithdrawalsSession` expose the
same lifecycle:

| Member | Description |
| --- | --- |
| `present(activity: Activity): ZerohashSession?` | Launches the flow's WebView activity; returns `null` if the JWT fails validation |
| `cancel()` | Closes the session if it is active |
| `isActive(): Boolean` | Whether the session is currently active |
| `allowList: ZerohashAllowList` | Optional `configure*` param — hosts the WebView may navigate to / load from (defaults to `ZerohashAllowList.DEFAULT`) |

## Architecture

Each flow renders the zerohash mobile web app inside a hardened `WebView` and
communicates with it over a JavaScript↔Kotlin bridge. External-source OAuth
(where a flow needs to authenticate against a third-party provider) is handed
off to Chrome Custom Tabs rather than run inside the SDK's WebView.

## Local development

`app/` is a single-screen demo with one button per flow. Run it on an
emulator/device:

```bash
./gradlew :app:installDebug
```

## License

Licensed under the zerohash Android Wrapper License — a proprietary license.
See [`LICENSE`](LICENSE) for the full terms. Questions: legal@zerohash.com.
