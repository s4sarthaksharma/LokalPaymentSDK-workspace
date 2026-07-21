# Generic WebView + JS Bridge (`:webview`) — plan

## Goal
A payment-agnostic, pure-KMP (no Compose) WebView + JS bridge module. It is a
reusable building block; a future **webview-based payment gateway** module will
consume it (`api(project(":webview"))`) and implement `PaymentGatewayHandler` on
top. `:webview` itself knows nothing about payments.

## Rules it follows (same as the rest of the SDK)
- Host never receives or supplies a platform view. The module presents the
  WebView itself — Android: internal translucent-less proxy `Activity`; iOS:
  present onto `topmostViewController()`.
- expect/actual **public** factory + interface (public, not internal, because a
  *separate* Gradle module consumes it).
- No vendor pod, no Swift bridge — iOS uses `platform.WebKit.*` directly.

## Public API (`commonMain`, package `com.getlokalapp.paymentsdk.webview`)
- `WebViewRequest` — sealed: `Url(url, headers)`, `Html(html, baseUrl)`, `Post(url, body)`.
- `JsBridgeHandler` — `val name`, `fun onMessage(payload: String, reply: (String) -> Unit)`.
- `WebViewListener` — `onPageStarted/onPageFinished/onNavigation(url): Boolean/onClosed/onError`.
- `WebViewConfig` — `bridgeName`, `handlers`, `listener`, `allowedOrigins`, JS/DOM toggles.
- `WebViewSession` — `load(request)`, `evaluateJavascript(script, onResult)`, `close()`.
- `expect fun createWebViewSession(config): WebViewSession`.

## Unified bridge
One JS shim (in `commonMain`, parameterized by `bridgeName`) exposes
`window.<bridgeName>.postMessage(name, payload): Promise`. Both platforms funnel
through a fixed transport channel `LokalNativeTransport` carrying a JSON string
envelope `{name, payload, id}`; native routes to the matching handler by `name`
and resolves the JS promise via `window.__lokalBridgeReply__(id, result)`.
- Android: `addJavascriptInterface(obj, "LokalNativeTransport")` + shim injected
  at `onPageStarted`; reply via `evaluateJavascript`.
- iOS: `WKUserContentController.addScriptMessageHandler(_, "LokalNativeTransport")`
  + shim as a `WKUserScript` at document start; reply via `evaluateJavaScript`.
Envelope parse, reply-script building, and origin gating live in a shared
`BridgeDispatcher` — only the transport differs per platform.

## Security defaults
- `allowedOrigins` (null = allow all) gates handler dispatch by current URL prefix.
- iOS user script is `forMainFrameOnly = true`; iOS handler removed on dismiss
  (avoids the `WKScriptMessageHandler` retain cycle).

## Files
- `settings.gradle.kts` — `include(":webview")`.
- `webview/build.gradle.kts` — mirrors `:gateways:razorpay-checkout` minus vendor pod &
  version-baking; adds `kotlin.serialization`; `api(project(":shared"))`.
- `commonMain`: `WebViewRequest.kt`, `JsBridgeHandler.kt`, `WebViewListener.kt`,
  `WebViewConfig.kt`, `WebViewSession.kt`, `BridgeShim.kt`, `BridgeDispatcher.kt`.
- `androidMain`: `AndroidWebViewSession.kt`, `WebViewActivity.kt`, `AndroidManifest.xml`.
- `iosMain`: `IosWebViewSession.kt`.

## Not in scope (deferred to the gateway module)
- `PaymentGatewayHandler` / `LokalPaymentSdk` registration, `Flow` wrapping.
- Demo app wiring (no consumer yet).

## Known limitations (v1)
- Android shim injection at `onPageStarted` (not true document-start); fine for
  pages that call the bridge after DOM ready. Hardening: androidx.webkit
  `WebViewCompat.addDocumentStartJavaScript` later.
- `allowedOrigins` is a prefix match, not full origin parsing.
- `WebViewRequest.Post` supported best-effort on both platforms.
