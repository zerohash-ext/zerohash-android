# zerohash-android

Kotlin SDK that embeds the zerohash **Fund** flow (account funding /
pay-to-settle) into a native Android app. It renders the zerohash mobile web app
inside a hardened `WebView` and bridges it to a typed Kotlin API.

| | |
| --- | --- |
| **Package** | `com.zerohash.sdk` |
| **Min SDK** | Android API 21 (Android 5.0+) |
| **Language** | Kotlin 1.9+ |
| **Build** | Gradle 8.2+, JDK 17 |

## Quick start

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

## Architecture

See [`CLAUDE.md`](CLAUDE.md) for the bridge protocol and OAuth details, and
[`ZEROHASH_ANDROID_OVERVIEW.md`](ZEROHASH_ANDROID_OVERVIEW.md) for the full
integration guide.

> **Note:** This SDK is a port of `connect-android`. The Fund flow differs from
> Auth/Recovery/Withdrawal only in that its web app renders the funding UI inside
> an iframe — that iframe layer lives on the web side, so the Android bridge is
> the same.

## Local development

`app/` is a single-screen demo. Run it on an emulator/device:

```bash
./gradlew :app:installDebug
```

Local test-only files (Netskope CA, JitPack switch, SDK-source banner) are
documented in [`DO_NOT_COMMIT.md`](DO_NOT_COMMIT.md).
