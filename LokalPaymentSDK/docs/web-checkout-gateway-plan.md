# `:gateways:web-checkout` — hosted web payment gateway — plan

## Goal
A `PaymentGatewayHandler` that runs the existing hosted gateway web app
(`payment-web`, e.g. `https://dev-web-pay.dostt.in`) inside the `:webview`
module and maps its reported result back into the SDK's `PaymentResult`. The web
app is **provider-agnostic** (Dodo today, Stripe next) and holds all
provider/security logic; this SDK gateway is thin — decode config, open the URL,
map ~7 events.

The web app is documented separately (React + Vite, runs in a WebView, redirects
to the provider's hosted checkout, reports exactly one normalized event). This
plan covers only the KMP/native side.

## Locked decisions
- **Both platforms** (Android + iOS) consume the same hosted page from the native
  SDK. Same bridge path on both.
- **Gateway URL comes from the backend** (`gatewayConfig`), fully built — the SDK
  does **not** assemble URLs or hold environment domains. The SDK opens it as-is.
- **`bridgeHosts` is derived from the validated absolute HTTPS gateway URL's
  normalized scheme/host** — only a main frame on the gateway host may post
  events; provider pages and all subframes cannot.
- `PAYMENT_SUCCESS → PaymentResult.Success`.
- Module `:gateways:web-checkout`, gateway code `web_checkout`.
- `provider` (dodo/stripe) stays **opaque** inside `gatewayConfig` — the SDK never
  names a provider, mirroring the web app.
- The SDK does **not** duplicate `checkoutUrl` validation or provider status
  normalization — the web app owns both.

## The bridge — reusing the RN contract
The web app reports via `window.ReactNativeWebView.postMessage(JSON.stringify({
name, payload }))` (RN's fire-and-forget, single-string contract). That body is
exactly our `BridgeEnvelope` minus the optional `id`, so:

- `:webview` injects (at document start) a shim defining
  `window.ReactNativeWebView.postMessage(s)` that relays the raw string `s` over
  our existing `LokalNativeTransport` channel.
- `BridgeDispatcher` parses `{name, payload}` and **routes by `name`** — no
  dispatcher changes.
- `:gateways:web-checkout` registers one `JsBridgeHandler` per event name. `reply` unused.

Rejected alternative: detecting completion by intercepting `/callback` + `/cancel`
navigations. It can't capture `PAYMENT_GATEWAY_ERROR` (reported only over the
bridge, no navigation to intercept) and would force us to duplicate each
provider's status vocabulary native-side. The bridge path is complete and
design-aligned.

## `:webview` additions required (generic, no provider/RN knowledge baked in)
1. **`WebViewConfig.userScripts: List<String>`** — extra JS injected at
   document-start alongside the existing bridge shim (Android: in `onPageStarted`;
   iOS: a `WKUserScript` at `.atDocumentStart`). `:gateways:web-checkout` supplies the
   `window.ReactNativeWebView` shim here — `:webview` stays RN-agnostic.
2. **Optional close/cancel chrome** (config-gated, e.g. `showCloseControl`) — a
   slim top bar / ✕ so the user always has an escape from a full-screen payment
   modal (essential on iOS, which has no hardware back; enabled on Android too for
   symmetry). Tap → `close()` → `onClosed`. Must live in `:webview` since it owns
   presentation.

Not doing for v1: an `onBackPressed` hook (provider-specific back handling). Use
`:webview`'s existing default — `goBack()` if in-page history exists, else close.

## Behavior decisions
- **Android back:** default `:webview` behavior (`goBack`, else close). We do
  **not** replicate the web app's Dodo-specific `[title="Go back"]` click — it's
  brittle and breaks provider-agnosticism; that belongs in the RN app.
- **Cancellation:** the web app's `/cancel` → `PAYMENT_CANCELLED` event is the
  clean path. A webview **closed with no prior event** (hardware back at root, or
  the close control) also maps to `PaymentResult.Cancelled`.
- **Advisory result:** consistent with the doc and the UPI-intent gateway, the
  result is advisory — the host verifies final state with its backend. Reflected
  in KDoc; `Success` is still emitted for `PAYMENT_SUCCESS`.

## Event → PaymentResult mapping
| Web event | PaymentResult |
|---|---|
| `PAYMENT_SUCCESS` | `Success(paymentId)` |
| `PAYMENT_FAILED` | `Failure(code, message)` |
| `PAYMENT_EXPIRED` | `Failure(code = "expired", …)` |
| `PAYMENT_PROCESSING` | `Pending` (verify with backend) |
| `PAYMENT_PENDING` | `Pending` (verify with backend) |
| `PAYMENT_CANCELLED` | `Cancelled` |
| `PAYMENT_GATEWAY_ERROR` | `Failure(code = reason, …)` |
| (webview closed, no event) | `Cancelled` |

## `:gateways:web-checkout` module shape
- Build file mirrors `:gateways:razorpay-checkout` minus vendor pod/version-baking;
  `api(project(":webview"))`; `kotlin.serialization`. iOS: pure KMP, no pod.
- `commonMain`:
  - `WebCheckoutSdk : PaymentGatewayHandler` — self-registers; `readiness()` Ready;
    `pay(gatewayConfig): Flow<PaymentResult>` via `callbackFlow`.
  - `WebCheckoutConfig` — decodes the ready gateway URL (+ anything else backend
    sends) from `gatewayConfig` with `lenientJson`.
  - `WebCheckoutEvents` — the 7 event-name constants + the `JsBridgeHandler`
    factory that maps each to a `PaymentResult` and completes the flow.
  - `ReactNativeBridgeShim` — the `window.ReactNativeWebView` JS shim string passed
    via `WebViewConfig.userScripts`.
- `pay()` flow: decode config → validate absolute HTTPS URL → build
  `WebViewConfig(handlers = 7 events, userScripts = [rn shim], bridgeHosts =
  [gatewayUrl scheme/host], listener)` →
  `createWebViewSession(config).load(WebViewRequest.Url(gatewayUrl))` →
  `trySend`+`close()` on first event; `onClosed` with no event → `Cancelled`;
  `awaitClose { session.close() }`.
- Android auto-registration: `WebCheckoutInitializer` + manifest `<meta-data>`
  (copy `RazorpayCheckoutInitializer`). iOS: `@EagerInitialization` file (copy
  `RazorpayCheckoutEagerInit`).
- `PaymentGateway`: add `WEB_CHECKOUT` + `fromCode("web_checkout")` in `:shared`.

## Not in scope / open
- Exact `gatewayConfig` field name(s) for the ready URL — confirm with backend
  (assume a single ready-to-open URL field).
- Demo wiring for `:gateways:web-checkout` — separate step; needs a real/staging session.
- No third-party pod, host-contributor, or iossrc (pure KMP + `:webview`).

## Verification (at the end, on request)
- Build both targets.
- Drive the staging gateway URL end-to-end (success / cancel / error) on Android
  + iOS simulator, asserting the mapped `PaymentResult` on the flow.
