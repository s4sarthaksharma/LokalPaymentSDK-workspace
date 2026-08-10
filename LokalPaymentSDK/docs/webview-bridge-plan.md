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
- `WebViewConfig` — `bridgeName`, `handlers`, `listener`, required structured
  `bridgeHosts`, JS/DOM toggles.
- `WebViewSession` — `load(request)`, `evaluateJavascript(script, onResult)`, `close()`.
- `expect fun createWebViewSession(config): WebViewSession`.

## Unified bridge
One JS shim (in `commonMain`, parameterized by `bridgeName`) exposes
`window.<bridgeName>.postMessage(name, payload): Promise`. Both platforms funnel
through a fixed transport channel `LokalNativeTransport` carrying a JSON string
envelope `{name, payload, id}`; native routes to the matching handler by `name`
and resolves the JS promise via `window.__lokalBridgeReply__(id, result)`.
- Android: `WebViewCompat.addWebMessageListener(..., "LokalNativeTransport", ...)`
  exposes the transport; native dispatch accepts only an authorized main-frame
  `sourceOrigin`; reply uses `evaluateJavascript`.
- iOS: `WKUserContentController.addScriptMessageHandler(_, "LokalNativeTransport")`
  + shim as a `WKUserScript` at document start; reply via `evaluateJavaScript`.
Envelope parse and reply-script building live in a shared `BridgeDispatcher`.
Frame-aware origin authorization happens at each platform transport boundary.

## Security defaults
- `bridgeHosts` gates handler dispatch by the sending main frame's exact,
  normalized HTTPS scheme/host identity. An explicitly empty set attaches no
  bridge; there is no allow-all default.
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
- **V1 release decision:** do not fall back to `addJavascriptInterface`; security
  takes precedence over checkout availability on unsupported WebView providers.
  Rollout must track `secure_web_message_unavailable` (logged with the installed
  provider version and returned through `onError`) and offer another payment
  method. WebView-version distribution should be reviewed before broad rollout.
- Secure Android bridge messaging requires WebView provider version 85 or newer,
  where `WEB_MESSAGE_LISTENER` is supported. Older or update-disabled providers
  fail closed with `secure_web_message_unavailable`; Web Checkout hosts must map
  that failure to another available payment method. No `addJavascriptInterface`
  fallback is provided because it cannot authenticate the sending frame.
- Android shim injection at `onPageStarted` (not true document-start); fine for
  pages that call the bridge after DOM ready. AndroidX WebKit is now available,
  but document-start migration remains deferred until its frame/origin rules can
  preserve the SDK's deliberate port-agnostic host policy.
- Android uses `WebViewCompat.addWebMessageListener` and authorizes the engine's
  `sourceOrigin` only when `isMainFrame`; iOS authorizes
  `WKScriptMessage.frameInfo.securityOrigin` only when `mainFrame`. Subframes
  cannot dispatch native messages.
- Android registers the transport with the WebKit `*` injection rule because an
  omitted port in an AndroidX origin rule means port 443, while this SDK's host
  identity deliberately ignores ports. The callback remains fail-closed: it
  rejects every subframe and every source scheme/host outside `bridgeHosts`
  before parsing or dispatching the message.
- `WebViewRequest.Post` supported best-effort on both platforms.
