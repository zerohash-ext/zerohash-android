# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **Zerohash SDK for Android** — a Kotlin SDK that gives a native
Android app a drop-in integration with the zerohash **Fund** flow (account
funding / pay-to-settle). The SDK renders the zerohash **mobile web app**
(`apps/mobile` in `zerohash-sdk`) inside a hardened WebView and communicates
with it over a JavaScript↔Kotlin bridge.

**Package**: `com.zerohash.sdk`
**Min SDK**: Android API 21 (Android 5.0+)
**Language**: Kotlin 1.9+
**Build**: Gradle 8.2+ with JDK 17

> This SDK was ported from the `connect-android` SDK (`xyz.connect.sdk`), which
> wraps the Auth / Recovery / Withdrawal flows. The architecture is identical —
> the only product difference is that Fund's web app embeds the funding flow in
> an **iframe** (the `#fund` route renders the `<zerohash-fund>` web component,
> which loads the Fund iframe). That iframe layer lives entirely on the web
> side; the Android bridge contract is unchanged.

## How it works

```
Host App → ZerohashSDK.configureFund() → ZerohashFundSession → [Intent] → WebViewActivity
                                                                              ↓
                                          loads  https://sdk-cdn.zerohash.com/mobile/#fund
                                                                              ↓
                        WebViewMessageHandler  (NativeAndroid JS↔Kotlin bridge)
                        WebViewOAuthManager     (Chrome Custom Tabs for external-source OAuth)
```

The WebView loads the zerohash-branded `apps/mobile` deployment
(`sdk-cdn.zerohash.com` / `sdk-cdn.cert.zerohash.com` — the same build artifact
is also served from the legacy `sdk.connect.xyz` / `sdk.sandbox.connect.xyz`
hosts). Its `#fund` route maps the native `production`/`sandbox` env to the Fund
SDK's `prod`/`cert` and renders the Fund web component + iframe.

### Bridge protocol (matches `apps/mobile`)

- **web → native** (`window.NativeAndroid.postMessage`): `page-ready`,
  `navigate`, `close`, `error` (`{errorCode, reason}`), `event`
  (`{...data, eventType}`), `deposit` (Fund completion — `FundCompletedData`).
- **native → web** (`window.postMessage`): `jwt` (`{token, env}`), `config`
  (`{theme}`).

### OAuth

Fund embeds the shared integrations-flow ("auth as a feature") external-source
deposit flow, which uses OAuth via **Chrome Custom Tabs** (not the WebView). The
provider redirects back via `connectsdk-oauth://callback` — a scheme dictated by
the backend (connection-service). `OAuthCallbackActivity` is the only exported
activity; it forwards the callback to the non-exported `WebViewActivity`.

**Exception — Coinbase automation login (Apple):** The scraping login WebView
(`automation/CoinbaseLoginActivity.kt`) supports "Sign in with Apple", which
Coinbase drives via a `window.open` popup on `appleid.apple.com` that returns
its result to `window.opener`. This popup is NOT handled by Chrome Custom Tabs
(a separate browser has no `window.opener` link back to the login page).
Instead the login WebView enables `setSupportMultipleWindows(true)` and its
`WebChromeClient.onCreateWindow` delegates to `automation/AuthPopupWindow.kt`,
a provider-agnostic host that presents the popup in a child WebView sharing the
process-wide cookie jar (the Android counterpart of iOS `PopupWebViewController`).
`AuthPopupWindow` carries no provider specifics, so a future Kraken login reuses
it unchanged.

## Essential Commands

```bash
# Build the SDK module
./gradlew :zerohashsdk:build

# Run unit tests
./gradlew :zerohashsdk:test

# Build & install the demo app
./gradlew :app:installDebug

# Logcat
adb logcat | grep -E "ZerohashDemo|WebView|OAuthHandler"
```

## File Organization

```
zerohashsdk/src/main/java/com/zerohash/sdk/
├── ZerohashSDK.kt          # Public API (configureFund)
├── ZerohashSDKTypes.kt     # Theme, Environment, ZerohashError, GenericEvent, ...
├── ZerohashAllowList.kt    # Host allow-list
├── fund/                   # Fund session + types
│   ├── FundTypes.kt        # FundCallbacks, FundCompletedEvent, FundCallbackHandler
│   └── ZerohashFundSession.kt
├── oauth/
│   └── OAuthHandler.kt     # Chrome Custom Tabs OAuth
├── ui/                     # WebViewActivity + bridge + OAuth manager + loading
└── internal/               # JwtValidator, Base64Util, Constants
```

## Module Structure

- **zerohashsdk/** — Main SDK module (library)
- **app/** — Demo application (single-screen Fund tester)

The demo app depends on the SDK via `implementation(project(":zerohashsdk"))`
by default (see `app/build.gradle.kts`, `useJitpack`).

## Test-only scaffolding

See **`DO_NOT_COMMIT.md`** — the `app/src/debug/` Netskope CA overlay, the
JitPack switch, and the on-screen SDK-source banner are local test infra carried
over from connect-android and must not land in a clean release commit.

## Atlassian (Jira / Confluence) MCP

When connected to the Atlassian MCP server, use these fixed values to skip
discovery calls, save tokens, and cap result sizes:

- **MUST** use cloudId = `a736fade-c465-476e-bba9-42baf3dcc689`
  (site `seedcx.atlassian.net`) — do NOT call `getAccessibleAtlassianResources`.
- **MUST** use Jira project key = `AUTH`.
- **MUST** use Confluence spaceId = `3646783586` (the "Engineering" space).
- **MUST** use `maxResults: 10` / `limit: 10` for ALL Jira JQL and Confluence
  CQL search operations.
