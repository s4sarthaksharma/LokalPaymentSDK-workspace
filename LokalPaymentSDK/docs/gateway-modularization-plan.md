# LokalPaymentSDK — Gateway Modularization Plan

> **⚠️ HISTORICAL — this plan has been executed and superseded.** The module
> split it describes (`:shared` / `:gateways:razorpay-checkout` / `:gateways:razorpay-customui`)
> shipped, but the final design diverged from this plan: it added a
> `PaymentGatewayHandler` interface + `LokalPaymentSdk` registry (this doc
> proposed "by convention, not a shared interface"), a `LokalPaymentResult`
> envelope, and a typed `PaymentOrder` (this doc still had `pay()` taking raw
> `orderResponseJson`). For current state and how to add a gateway, read
> [`adding-a-new-gateway.md`](adding-a-new-gateway.md). Kept only as a record of
> *why* the split happened and the `com.razorpay:customui` vs
> `com.razorpay:checkout` artifact discovery (the UPI Intent `Razorpay` class
> lives in a separate Maven coordinate).

Source pattern: `matrimony-kmp` (`/Users/sarthaksharma/StudioProjects/matrimony-kmp`),
`moko-permissions` (`https://klibs.io/project/icerockdev/moko-permissions`)

## Context

`:shared` currently bundles everything behind one Gradle dependency: core
models (`PaymentResult`, `PaymentPresenter`, `PaymentGateway`, ...) *and* the
full Razorpay hosted-Checkout implementation, including the
`com.razorpay:checkout` Android dependency and the `razorpay-pod` CocoaPod. A
host app that only wants hosted Checkout still pays for everything.

The goal is to restructure this the way `moko-permissions` ships — a small
core module plus one opt-in leaf module per capability — so a future
custom-UPI-UI integration (Razorpay's native UPI Intent flow, the way
`matrimony-kmp` already does it in `AndroidRazorpayPaymentClient.kt`) can ship
as its own module without forcing every consumer to pull it in.

I checked `matrimony-kmp`
(`composeApp/src/androidMain/.../razorpay/AndroidRazorpayPaymentClient.kt`,
`RazorpayUtil.kt`, `PaymentsHelper.kt`) as the reference implementation for
the UPI Intent flow, and checked Razorpay's iOS SDK headers
(`Razorpay-Swift.h` in the `razorpay-pod` xcframework) for iOS parity.
Finding: iOS has no equivalent of Android's UPI Intent (there's no API to
resolve installed UPI apps and hand off via a deep link the way
`PackageManager` does). Razorpay's iOS answer is a completely different, much
larger product called **UPI Turbo** (account linking, native PIN entry, token
plugins) that `matrimony-kmp` hasn't integrated at all. Decision: scope UPI
Intent as **Android-only for v1**, but keep the module boundary clean so a
future `:razorpay-upi-turbo` (iOS) module can be added later without
reshaping this one.

## Target module layout

```
:shared              — core, gateway-agnostic (unchanged name, slimmed contents)
:gateways:razorpay-checkout   — existing hosted Checkout flow, moved out of :shared as-is
:gateways:razorpay-customui — new module, Android-only, modeled on matrimony-kmp's CustomUi client
```

Both leaf modules depend on `:shared` via `api(project(":shared"))` (not
`implementation`) because `Flow<PaymentResult>` — a `:shared` type — appears
in their own public `pay(...)` signatures. Neither leaf module depends on the
other. `:shared` will no longer reference Razorpay at all.

## `:shared` — strip down to gateway-agnostic core

Keep as-is (already gateway-agnostic):
- `PaymentPresenter.kt` (+ `.android.kt`/`.ios.kt` actuals)
- `model/{PaymentResult, PaymentError, CancelReason, PaymentGateway, CreateOrderResponse, CreateOrderResponseJson}`

Remove:
- `LokalPaymentSdk.kt` — this is the piece that currently hardcodes the
  `RAZORPAY_CHECKOUT` dispatch (`shared/src/commonMain/.../LokalPaymentSdk.kt:34-68`),
  which is exactly the coupling that prevents Razorpay from being optional.
  Replace with a tiny `object PaymentSdkInfo { const val VERSION = "0.0.1" }`
  so the demo's `LokalPaymentSdk.VERSION` reference has a new,
  gateway-agnostic home.
- The `razorpay-pod` cocoapods block and `implementation(libs.razorpay.checkout)`
  Android dependency in `shared/build.gradle.kts` — core no longer touches
  Razorpay, so both go away. `:shared` keeps its iOS targets and a (now
  Razorpay-free) `cocoapods {}` block purely to keep publishing its own iOS
  framework for `PaymentPresenter`.

New contract each leaf module fulfills (by convention, not a shared interface
— matches how `matrimony-kmp`'s own `PaymentsHelper.createOrder()` already
switches on `PaymentGatewayConfig` and calls the right client itself):

```kotlin
fun pay(orderResponseJson: String, /* platform presenter/context */): Flow<PaymentResult>
```

The host app parses `CreateOrderResponse.gateway` (via
`parseCreateOrderResponse`, staying in `:shared`) and calls whichever gateway
module's `pay()` matches.

## `:gateways:razorpay-checkout` — move existing code as-is

Straight move, package `com.getlokalapp.paymentsdk.razorpay` unchanged:
- commonMain: `RazorpayCheckoutClient.kt`, `RazorpayCheckoutClientFactory.kt`,
  `RazorpayCheckoutConfig.kt`, `RazorpayErrorCodes.kt`,
  `RazorpayPaymentResultListener.kt`, `RazorpayResultMapper.kt`,
  `CreateOrderResponseMapper.kt` (the `toRazorpayCheckoutConfig()` extension)
- androidMain: `AndroidRazorpayCheckoutClient.kt`, `JsonObjectConversions.kt`,
  `RazorpayCheckoutActivity.kt` (the translucent proxy-Activity + `RazorpayCheckoutBridge`)
- iosMain: `IOSRazorpayCheckoutClient.kt`, `JsonObjectConversions.kt`,
  `RazorpayCheckoutClientFactory.ios.kt`
- commonTest: `RazorpayResultMapperTest.kt`, `CreateOrderResponseMapperTest.kt`

New: `RazorpayCheckoutSdk.kt` (commonMain) — the `pay()` facade, essentially
today's `LokalPaymentSdk.pay()` body (`LokalPaymentSdk.kt:34-68`) moved here
unchanged, including the "unsupported_gateway" guard for anything that isn't
`RAZORPAY_CHECKOUT`.

`build.gradle.kts`: same plugins as today's `shared/build.gradle.kts`
(`kotlin.multiplatform`, `android.kotlin.multiplatform.library`,
`kotlin.serialization`, `native.cocoapods`, `maven-publish`), with the
`razorpay-pod` cocoapods block and `libs.razorpay.checkout` Android
dependency moved here from `:shared`.

## `:gateways:razorpay-customui` — new module, Android-only

No iOS targets, no cocoapods block — just `androidLibrary {}` (this is a
supported single-platform KMP module shape, consistent with the rest of the
project's Gradle conventions). Modeled directly on `matrimony-kmp`'s
`AndroidRazorpayPaymentClient.kt` (CustomUi branch) and `RazorpayUtil.kt`:

- commonMain:
  - `RazorpayCustomUiConfig.kt` — `razorpayKey: String`, `data: JsonObject`
    (parsed from `CreateOrderResponse.gatewayConfig` the same lenient way
    `CreateOrderResponseMapper.kt:17-23` does today, gated on
    `PaymentGateway.RAZORPAY_CUSTOM_UI` instead of `RAZORPAY_CHECKOUT` — that
    enum value already exists at `model/PaymentGateway.kt:12`, reserved and
    unused until now)
  - `RazorpayCustomUiResultListener.kt`, `RazorpayCustomUiResultMapper.kt`
    — **not** shared with `:gateways:razorpay-checkout`'s versions: `Razorpay.submit()`'s
    callback shape differs from `Checkout.open()`'s (richer `PaymentData`,
    different cancellation code). Confirmed from `matrimony-kmp`'s
    `PaymentsHelper.kt:66-67` — UPI intent cancellation is error code `5`,
    not `0` like Checkout.
- androidMain:
  - `AndroidRazorpayCustomUiClient.kt` — wraps
    `Razorpay(activity, key).apply { setWebView(webView) }.submit(data, listener)`,
    same shape as `AndroidRazorpayPaymentClient.kt:26-34` in matrimony-kmp.
  - `RazorpayCustomUiSdk.kt` — the `pay()` facade:
    `fun pay(orderResponseJson: String, activity: Activity, webView: WebView): Flow<PaymentResult>`.
    Takes `Activity`/`WebView` directly rather than `:shared`'s
    `PaymentPresenter` — this module is Android-only, so the multiplatform
    presenter abstraction doesn't buy anything here and would just add an
    unwrap step.

Android dependency: `implementation(libs.razorpay.customui)` — **not** the
same artifact as `:gateways:razorpay-checkout`. Verified against matrimony-kmp's own
`build.gradle.kts` (which declares both `razorpay-checkout` *and*
`razorpay-custom-ui` side by side) and by inspecting `customui-core`'s
`classes.jar`: the `Razorpay` class (`submit()`/`setWebView()`) lives in
`com.razorpay:customui` (pulling in `customui-core` transitively), a
completely separate Maven coordinate from `com.razorpay:checkout`'s
`Checkout` class. This makes the module split even more valuable than
originally scoped — a host that only wants hosted Checkout now avoids a
second third-party SDK dependency entirely, not just dead code.

**Integration requirements this module places on the host (different from
Checkout's — call this out explicitly since it's a real behavioral change for
consumers):**

> **Superseded during implementation.** The shipped `:gateways:razorpay-customui` module
> places *no* extra requirements on the host: it owns an internal proxy Activity
> (`RazorpayCustomUiActivity`) that hosts the `WebView` and forwards
> `onActivityResult` itself, same as `:gateways:razorpay-checkout`. The two requirements
> below reflect the original plan (matching matrimony-kmp), not the final design.
1. Host must supply a `WebView` — required by Razorpay as a JS bridge for
   `submit()`, even though the SDK never shows it directly.
2. Host's Activity must forward `onActivityResult` to the client (some UPI
   apps return control via an Android `Intent` result). Unlike
   `:gateways:razorpay-checkout`, there's no SDK-owned proxy Activity here — the host
   Activity is the one hosting the WebView, so the SDK can't interpose one.
   Expose this as `AndroidRazorpayCustomUiClient.onActivityResult(...)`,
   matching `AndroidRazorpayPaymentClient.kt:56-58` in matrimony-kmp.

## Gradle wiring

- `settings.gradle.kts`: add `include(":gateways:razorpay-checkout")` and
  `include(":gateways:razorpay-customui")`.
- Each new module's `build.gradle.kts`: `group = "com.getlokalapp.paymentsdk"`,
  its own `version`, its own Android `namespace` (e.g.
  `com.getlokalapp.paymentsdk.checkout` / `...customui`).
- `libs.versions.toml`'s existing `razorpay-checkout` catalog entry
  (`gradle/libs.versions.toml:9,16`) is reused by both leaf modules unchanged.

## Consumer impact (`LokalPaymentSDKDemo`)

- `LokalPaymentSDKDemo/gradle/libs.versions.toml:21` declares
  `lokalpaymentsdk-shared = "com.getlokalapp.paymentsdk:shared"` — add
  matching catalog entries for the two new Maven coordinates, and the demo's
  publish step (`./gradlew :shared:publishToMavenLocal`, per the comment in
  `LokalPaymentSDKDemo/settings.gradle.kts`) becomes
  `./gradlew :shared:publishToMavenLocal :gateways:razorpay-checkout:publishToMavenLocal`
  (adding `:gateways:razorpay-customui:publishToMavenLocal` once the demo exercises it).
- `composeApp/build.gradle.kts:53` (`implementation(libs.lokalpaymentsdk.shared)`)
  gets a second line for `razorpay-checkout` (the demo's Pay button only
  exercises hosted Checkout today, so `razorpay-customui` isn't wired into
  the demo in this pass).
- `App.kt:20,54,61` — `LokalPaymentSdk()` / `LokalPaymentSdk.VERSION` becomes
  `RazorpayCheckoutSdk` (from the new module) / `PaymentSdkInfo.VERSION`
  (from `:shared`).

## Verification

1. `./gradlew :shared:build :gateways:razorpay-checkout:build :gateways:razorpay-customui:build`
   in `LokalPaymentSDK` — confirms each module compiles independently and
   `:shared` no longer pulls in Razorpay.
2. `./gradlew :gateways:razorpay-checkout:allTests` — the two relocated test files
   (`RazorpayResultMapperTest`, `CreateOrderResponseMapperTest`) still pass
   unchanged.
3. Publish all three to `mavenLocal`, update the demo's catalog + `App.kt` per
   above, run `LokalPaymentSDKDemo`'s Android and iOS targets, and exercise
   the "Pay with Razorpay" button end-to-end to confirm hosted Checkout still
   works after the split.
