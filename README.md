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
    implementation("com.zerohash:zerohash-android:1.0.3")
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
            environment = Environment.PRODUCTION,   // optional (default)
            theme = Theme.SYSTEM,                    // optional (default)
            callbacks = object : FundCallbacks {
                override fun onClose() { fundSession = null }
                override fun onError(error: ZerohashError) { /* show error */ }
                override fun onEvent(event: GenericEvent) { /* analytics */ }
                override fun onFundCompleted(event: FundCompletedEvent) {
                    // event.transactionId, event.assetSymbol, event.amount,
                    // event.depositAddress, event.network, event.fundId,
                    // event.notionalAmount
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

Only `jwt` and `callbacks` are required. `environment` defaults to `PRODUCTION`,
`theme` to `SYSTEM` (mapped to the web app's `auto`).

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
            environment = Environment.PRODUCTION,   // optional (default)
            theme = Theme.SYSTEM,                   // optional (default)
            callbacks = object : CryptoWithdrawalsCallbacks {
                override fun onClose() { withdrawalsSession = null }
                override fun onError(error: ZerohashError) { /* show error */ }
                override fun onEvent(event: GenericEvent) { /* analytics */ }
                override fun onWithdrawalCompleted(event: CryptoWithdrawalsCompletedEvent) {
                    // event.withdrawalRequestId, event.rawData
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
