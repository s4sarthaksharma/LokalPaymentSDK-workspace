# LokalPaymentSDK — Architecture Reference & Plan

> **⚠️ HISTORICAL — superseded, do not treat as current.** This was the v1
> planning doc. The shipped design has since evolved past it (a
> `PaymentGatewayHandler` interface, a `LokalPaymentSdk` registry, a
> `LokalPaymentResult` envelope, a typed `PaymentOrder`, and the module split
> — none of which this doc describes). The "v1 API sketch" (§3) and phase
> status (§5) are **out of date**. For current state and how to add a gateway,
> read [`adding-a-new-gateway.md`](adding-a-new-gateway.md). Kept only for the
> rationale and gotchas it still uniquely records: the matrimony-kmp reference
> pattern (§1), the double-open bug and cancel-vs-failure discipline (§1.4),
> and the iOS CocoaPods interop wrinkles + unverified `orderId`/`signature`
> extraction (§5, Phase 3).

Source pattern: `matrimony-kmp` (`/Users/sarthaksharma/StudioProjects/matrimony-kmp`)

This doc captures the payment architecture pattern proven in
matrimony-kmp, then adapts it into a concrete v1 plan for
`LokalPaymentSDK` — a standalone KMP library other Lokal apps will
depend on, rather than payment code embedded in one app.

---

## 1. Reference pattern: how matrimony-kmp does it

### 1.1 Common interface + platform actuals

Each gateway gets a `commonMain` interface, with Android/iOS implementations
wired in at the app layer via Compose `CompositionLocal` (not Koin — Koin
is reserved for the app's own business-logic classes):

- `JuspayPaymentClient` — `core/payments/juspay/JuspayPaymentClient.kt:41`
- `RazorpayPaymentClient` — `core/payments/razorpay/RazorpayUtil.kt:23`
  — `preload()`, `openCheckout`, `setPaymentResultListener`.
- `StoreKitManager` — `core/payments/storekit/StoreKitManager.kt:5`
  — true KMP `expect fun getStoreKitManager()`; exposes
  `transactionUpdates: Flow<PurchaseResult>` + `purchaseProduct()`.

Android's `AndroidRazorpayPaymentClient` wraps Razorpay's `Checkout`
(standard) or a `WebView`-based UPI-intent flow. iOS's
`IOSRazorpayPaymentClient` wraps the `razorpay-pod` CocoaPod. Wiring
happens once, at app start, in `MainActivity.kt` / `MainViewController.kt`,
which construct the platform client (passing in the Activity/WebView it
needs) and provide it down via `CompositionLocalProvider`.

**Important for us**: the Compose/CompositionLocal wiring is an app-layer
choice, not something intrinsic to the gateway clients themselves. Our
SDK core has no reason to depend on Jetpack Compose — see §3.

### 1.2 Gateway selection is server-driven

The client sends all available gateways in the create-order call, and
the backend responds with one concrete `PaymentGatewayConfig` (a sealed
type). The client pattern-matches and dispatches — zero local business
logic decides the gateway. This lets them roll out/A-B test gateways
server-side without a client release.

### 1.3 End-to-end flow (orchestrated by a single `PaymentsHelper`)

1. **Initiate** — UI calls `createOrder()` → backend returns a
   `PaymentGatewayConfig`.
2. **Dispatch by config type** → pushed through a Kotlin `Channel` the UI
   collects, which calls the platform client's `openCheckout`.
3. **Gateway callback → normalized result** — Razorpay success maps to
   `PaymentGatewayData.Razorpay.Checkout(paymentId, signature,
   orderRowId)`; failure path distinguishes user-cancellation codes (`0`,
   `5` for UPI) from real errors.
4. **Server-side validation** — every successful callback funnels into
   `ValidatePaymentUseCase` → POST with gateway-specific fields
   (payment_id/signature for Razorpay).
5. **UI resolution** — three distinct terminal states: **success**,
   **real failure** (dedicated screen, retry/support), **user
   cancellation** (routed separately to a win-back "nudge" UI — never
   conflated with failure).

### 1.4 Gotchas worth carrying forward

- Cancellation vs. failure must be classified at the gateway-callback
  layer using gateway-specific cancel codes, not inferred later.
- Guard against opening checkout twice / before prior state settled —
  matrimony had a real production bug here (now guarded + reported to
  Crashlytics in `AndroidJuspayPaymentClient.openHypercheckout`). Applies
  to Razorpay too: don't let a double-tap open two checkout sheets.
- A single shared "in-flight" flag, explicitly reset on every terminal
  branch (success/error/cancel) — easy to leak into a stuck state if a
  branch forgets to reset it.

---

## 2. Decisions for v1 (locked in)

| Decision | Choice | Why |
|---|---|---|
| Gateway scope | **Razorpay Checkout only.** No UPI Intent, no Juspay, no StoreKit yet. | Fastest path to a real, working, cross-platform (Android + iOS) v1. Other gateways slot into the same sealed-type pattern later without a redesign. |
| Networking ownership | **Revised: the SDK doesn't define a backend interface at all**, not even an abstract one. The host app calls its own backend however it wants (own networking stack, own DI), gets a `CreateOrderResponse` back, and hands that directly to the SDK. Same on the way out — the SDK hands the host raw gateway fields in `PaymentResult.Success`, and the host calls its own validate endpoint itself. `CreateOrderResponse` is the *only* type the SDK still owns from this area. | Originally had a `PaymentBackend` interface (`createOrder()`/`validatePayment()`) that the SDK called through — but that's an interface the SDK doesn't need to own to stay backend-agnostic; owning zero networking-shaped types (not even an abstract contract) is strictly less API surface for the same backend-agnostic property. `PaymentBackend`, `ValidationResult`, and `RazorpayPaymentData` were deleted; `PaymentOrder` was deleted too since its only purpose was being the input to the now-gone `createOrder()`. |
| Public API shape | **`Flow<PaymentResult>`** returned from `pay()`. | Idiomatic KMP/coroutines; leaves room to later model continuous states (e.g. a StoreKit-style restore stream) without an API break. |
| UI ownership | **UI-less — orchestration + state only.** SDK still invokes Razorpay's own native checkout sheet (that part is unavoidable — it's Razorpay's UI, not ours), but ships no custom success/failure/nudge screens. Host app builds those from the emitted `PaymentResult`. | Matrimony's outcome screens are bespoke/branded and not meaningfully reusable across apps; keeping the SDK surface small also avoids forcing a UI framework choice on consumers. |
| Credential injection | **No local key config, and no `configure()` step at all anymore.** `razorpayKey` arrives per-order inside `CreateOrderResponse.gatewayConfig`, same as matrimony — and since there's no `PaymentBackend` left to configure either, `pay()` just takes the already-fetched `CreateOrderResponse` directly. | Backend is already the sole source of the order + key (it's the same call that hits Razorpay's Orders API). Once `PaymentBackend` was removed there was nothing left for `configure()` to take. |
| Distribution | **Local/source dependency for now.** No Maven/CocoaPods publishing yet. | API is still taking shape — defer packaging until it stabilizes. |

---

## 3. v1 API sketch

```kotlin
// commonMain — entry point. No configure() step, no backend interface —
// the host app has already called its own backend by the time it calls
// pay(); the SDK only ever sees the response, never the request.
class LokalPaymentSdk {
    fun pay(order: CreateOrderResponse, presenter: PaymentPresenter): Flow<PaymentResult>
}

// What the host app got back from its own backend's create-order call.
// gatewayConfig stays opaque until parsed against `gateway` — mirrors
// matrimony's CreateOrderDto exactly, so the contract doesn't change
// shape when a second gateway is added.
data class CreateOrderResponse(
    val gateway: Int,               // PaymentGateway.RAZORPAY_CHECKOUT.value, etc.
    val gatewayConfig: JsonObject,   // parsed via CreateOrderResponse.toRazorpayCheckoutConfig()
)

// Parsed out of gatewayConfig for gateway == RAZORPAY_CHECKOUT. `data` is
// handed straight to Razorpay's Checkout.open() — the SDK never inspects it.
data class RazorpayCheckoutConfig(
    val razorpayKey: String,
    val data: JsonObject,
)

// Success carries the raw gateway fields, not a validated outcome — the
// host app takes these straight to its own backend's validate call.
sealed class PaymentResult {
    data class Success(val paymentId: String, val orderId: String?, val signature: String) : PaymentResult()
    data class Cancelled(val reason: CancelReason) : PaymentResult()
    data class Failure(val error: PaymentError) : PaymentResult()
}

// expect/actual — the one piece of platform UI context Razorpay's own
// checkout sheet requires. NOT Compose-specific.
expect class PaymentPresenter
// actual class PaymentPresenter(val activity: Activity)          // Android
// actual class PaymentPresenter(val viewController: UIViewController) // iOS
```

There is no `PaymentOrder` type anymore — since the SDK never calls
`createOrder()` itself, it never needed to know the shape of that
request, only the response.

---

## 4. Module/package layout (v1, trimmed to Razorpay Checkout only)

Building on the existing scaffold
(`shared/src/{commonMain,androidMain,iosMain}/kotlin/com/getlokalapp/paymentsdk`).
Status: Phases 1 and 2 implemented; iOS wiring (Phase 3) and the
orchestrator (Phase 4) still to come.

```
paymentsdk/
  LokalPaymentSdk.kt              // public entry point: pay(order: CreateOrderResponse, presenter) — not yet written (Phase 4)
  PaymentPresenter.kt              // ✅ expect: opaque handle, no members in commonMain
  model/                           // ✅ done — no backend/ package anymore, no PaymentBackend/ValidationResult/RazorpayPaymentData/PaymentOrder
    CreateOrderResponse.kt         // envelope: gateway: Int, gatewayConfig: JsonObject — mirrors matrimony's CreateOrderDto; the only type carried over from the old backend/ package
    PaymentGateway.kt              // enum RAZORPAY_CHECKOUT=1, STORE_KIT=2, RAZORPAY_CUSTOM_UI=3, JUSPAY=4 — mirrors matrimony's numbering
    PaymentResult.kt               // sealed: Success(paymentId, orderId, signature) / Cancelled / Failure
    PaymentError.kt
    CancelReason.kt
  razorpay/
    RazorpayCheckoutConfig.kt      // ✅ parsed from gatewayConfig: razorpayKey, data: JsonObject (opaque, handed straight to Checkout.open())
    CreateOrderResponseMapper.kt   // ✅ CreateOrderResponse.toRazorpayCheckoutConfig()
    RazorpayCheckoutClient.kt      // ✅ commonMain interface: openCheckout(config, presenter), setPaymentResultListener()
    RazorpayPaymentResultListener.kt // ✅ raw callback interface: onPaymentSuccess(paymentId, orderId, signature) / onPaymentError(code, description)
    RazorpayErrorCodes.kt          // ✅ PAYMENT_CANCELLED = 0, verified against matrimony's production constant — for Phase 4's cancel-vs-failure classification
  androidMain/.../razorpay/
    AndroidRazorpayCheckoutClient.kt  // ✅ wraps com.razorpay:checkout 1.6.41 — Checkout().setKeyID(razorpayKey).open(activity, data)
    JsonObjectConversions.kt          // ✅ kotlinx JsonObject -> org.json.JSONObject bridge (Checkout.open() needs org.json)
  iosMain/.../razorpay/
    IOSRazorpayCheckoutClient.kt      // ✅ wraps razorpay-pod 1.4.3 via RazorpayCheckout.initWithKey(...).open(data, displayController)
    JsonObjectConversions.kt          // ✅ kotlinx JsonObject -> plain Map (bridges to NSDictionary)
  orchestration/
    PaymentOrchestrator.kt         // toRazorpayCheckoutConfig() -> openCheckout() -> normalize raw gateway callback -> emit PaymentResult — Phase 4. No createOrder/validatePayment call — those are 100% host-owned now.
```

Kept deliberately narrow — no `juspay/`, no `storekit/` yet. Adding a
second gateway later means adding a new `PaymentGateway` entry (already
reserved), a new sealed branch wherever `gateway` is switched on, and a
new `<name>/` folder — not restructuring what already exists (same
lesson as matrimony's `PaymentGatewayConfig`/`PaymentGatewayData` sealed
hierarchies, and why `CreateOrderResponse` is a generic envelope rather
than a Razorpay-shaped struct).

`PaymentPresenter` is `expect class PaymentPresenter` with **no members
declared** in commonMain — Android's and iOS's `actual` add their own
constructors (`Activity` / `UIViewController`) freely, since nothing in
common code ever constructs one directly; it only ever receives one as
an opaque parameter from platform app code, same spirit as matrimony's
CompositionLocal wiring but without a Compose dependency.

Classification of Razorpay's raw error `code` into
`PaymentResult.Cancelled` vs `.Failure` is deliberately deferred to
Phase 4's orchestrator, not done in `AndroidRazorpayCheckoutClient` —
mirrors matrimony keeping that judgment in `PaymentsHelper`, not in
`AndroidRazorpayPaymentClient`.

---

## 5. Phased implementation plan

- **Phase 1 — Core model, no gateway yet.**
  `PaymentOrder`, `PaymentResult`, `PaymentError`, `CancelReason`,
  `PaymentBackend` interface, `CreateOrderRequest/Response`,
  `RazorpayPaymentData`, `ValidationResult`. Compiles and is unit-testable
  with a fake `PaymentBackend` — no real gateway SDK involved yet.

- **Phase 2 — `PaymentPresenter` + `RazorpayCheckoutClient` interface +
  Android actual.** Wire the real Razorpay Android SDK
  (`com.razorpay:checkout`), handle its activity-result callback, map
  Razorpay's own cancel/error codes into `PaymentResult.Cancelled` vs
  `.Failure` (carry forward the matrimony distinction — don't conflate
  them). Guard against double-open (§1.4).

- **Phase 3 — iOS actual.** ✅ done. `IOSRazorpayCheckoutClient` wraps
  the `razorpay-pod` CocoaPod (`1.4.3`, pinned to match matrimony) behind
  the same `RazorpayCheckoutClient` interface. Required switching the
  `shared` module onto the `org.jetbrains.kotlin.native.cocoapods`
  Gradle plugin (`Shared.podspec` is now generated from `shared/`,
  replacing the old raw `binaries.framework{}` export) and adding a real
  `iosApp/Podfile` (`pod 'Shared', :path => '../shared'` +
  `pod 'razorpay-pod'` as its transitive dependency, via `pod install`).
  **Consequence for host apps**: any app consuming this SDK on iOS now
  needs CocoaPods integration (a `Podfile` pointing at wherever they
  vendor `shared/`), not just a static-framework drag-in — that's a
  direct result of `razorpay-pod` only distributing via CocoaPods, not
  something we chose independently.
  Two interop wrinkles worth remembering if this ever needs touching
  again: (1) razorpay-pod's cinterop generates its own
  `objcnames.classes.UIViewController` symbol, distinct from
  `platform.UIKit.UIViewController` — bridging `PaymentPresenter`'s
  `viewController` into `Checkout.open()` needs an explicit
  `as objcnames.classes.UIViewController` cast (safe — same underlying
  ObjC class at runtime, just two separately-generated Kotlin views of
  it). (2) unlike Android's Java `PaymentData` (typed getters), iOS's
  success callback hands back a raw `NSDictionary` — `orderId`/
  `signature` are pulled out via the `razorpay_order_id`/
  `razorpay_signature` keys per Razorpay's public (cross-platform) API
  docs. **This extraction is unverified against a live payment** — matrimony's
  own iOS client doesn't attempt it either (it just stores the raw dict
  unparsed), so there was no working reference to confirm the key names
  against. Confirm against a real sandbox transaction before shipping.
  (3) The scaffolded `iosApp.xcodeproj` had a leftover "Compile Kotlin
  Framework" run-script build phase from the original non-CocoaPods
  template (calling `./gradlew :shared:embedAndSignAppleFrameworkForXcode`)
  plus a manual `FRAMEWORK_SEARCH_PATHS` pointing at
  `shared/build/xcode-frameworks/...`. Both are incompatible with
  CocoaPods-managed integration and had to be removed by hand from
  `project.pbxproj` (Xcode error: "Incompatible 'embedAndSign' Task with
  CocoaPods Dependencies") — this is a known, standard step when
  migrating an existing KMP+Xcode scaffold onto CocoaPods, not something
  specific to us. **Verified with a real build**: `xcodebuild -workspace
  iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator build` succeeds
  end-to-end after the fix — this is the one piece of iOS work in this
  doc actually confirmed by a full app build, not just `compileKotlinIos*`.

- **Phase 4 — `PaymentOrchestrator` + `LokalPaymentSdk.pay()`.** Tie it
  together: `toRazorpayCheckoutConfig()` → `openCheckout()` → normalize
  the raw gateway callback → emit terminal `PaymentResult` on the
  returned `Flow`, resetting in-flight state on every branch. No
  `createOrder()`/`validatePayment()` call inside this — both are
  100% host-owned now (§2), so the orchestrator's job shrank to just
  gateway dispatch + callback normalization.

- **Phase 5 — Reference integration in this repo's own sample apps.**
  Wire `androidApp`/`iosApp` (already scaffolded here) as the first real
  consumers — call a stubbed "backend" directly in the sample app code
  to produce a `CreateOrderResponse`, pass it into `pay()`, and render
  the result. This becomes the integration doc/example for other Lokal
  apps.

- **Phase 6 — Harden against a real backend.** Once a real
  create-order/validate-payment endpoint exists on the host side,
  validate the full flow end-to-end, including failure/cancel/retry
  edge cases, and confirm the iOS `orderId`/`signature` extraction
  (Phase 3, wrinkle 2) against a live sandbox transaction.

---

## 6. Deferred (explicitly out of scope for v1 — revisit later)

- Razorpay Custom UI flow (needs a `WebView`, Android-only).
- Juspay integration (Android-only gateway).
- StoreKit / Apple IAP (iOS-only, different purchase model entirely).
- Any bundled UI (success/failure/nudge screens).
- SDK-owned networking / bundled HTTP client.
- Maven/CocoaPods publishing and semantic versioning.
- Multi-tenant / multiple-merchant-key-in-one-process support.

---

*Next step: turn Phase 1 into tracked implementation tasks and start
there.*
