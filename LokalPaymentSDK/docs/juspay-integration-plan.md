# Juspay (HyperSDK / HyperCheckout) Integration Plan — `:juspay` module

> **HISTORICAL — partially superseded.** The module was implemented per this
> plan, then its Android/iOS platform clients and host-facing API were
> substantially reworked after implementation (no `JuspayPlatformHandle`, no
> SDK-owned `JuspayActivity` proxy — D10 below was reversed back toward
> matrimony-kmp's original persistent-`HyperServiceHolder` design, but with
> the host's Activity auto-tracked instead of passed in). `JuspaySdk` now
> takes only `initPayload`/`clientId`/`tenantId` — no platform handle — and
> calls `initiate` immediately on construction (real early-initiate on both
> platforms). The host's Activity must be a `FragmentActivity`. Treat the
> actual source under `juspay/src/` as ground truth over Steps 6, 8, 10, 12
> below; this doc is kept for the original rationale (Spikes, D-decisions,
> R-risks) which mostly still holds.
>
> **Audience:** an engineer/agent implementing the Juspay gateway in LokalPaymentSDK
> from scratch, with **no prior context** on this repo.
> **Status:** approved design, not yet implemented.
> **Authoritative companion docs:** read [`adding-a-new-gateway.md`](./adding-a-new-gateway.md)
> (the gateway playbook — the numbered rules below reference its §5 rulebook) and
> [`rulebook.md`](./rulebook.md) (the "no Compose in the SDK" hard constraint).
> Where this plan and those docs disagree about *Juspay specifically*, this plan
> wins; where they state a general SDK rule, the rule wins.

---

## 0. How to use this document

Work top to bottom. **Do Step 0 (de-risking spikes) before writing any module
code** — two parts of this integration (the iOS HyperSDK pod, and how the Android
HyperSDK classes reach the compile classpath) are genuinely unverified and could
force design changes. Everything after Step 0 assumes those spikes succeeded; if
they don't, stop and report back with what you found rather than forcing it.

> **Step 0 status: both spikes done, both succeeded.** See §9 (R1/R2) for the
> confirmed findings — real Maven coordinates for Android, and a real cinterop
> build against the iOS pod (gated on the `SKIP_HYPERSDK_VALIDATION` flag, see D8).

Every code block marked `// SKETCH` is a shape to adapt, not verbatim truth —
verify symbol names against the actual SDK version you resolve. Blocks marked
`// COPY` can be lifted almost verbatim from the named reference file.

---

## 1. Context — what this SDK is and the contract you implement against

LokalPaymentSDK is a **gateway-agnostic core (`:shared`)** plus **one opt-in leaf
module per gateway**. The core knows nothing about any gateway; gateways know the
core and self-register. You are adding a new leaf module `:juspay`. **You do not
edit `:shared`** (the one allowed exception — a `PaymentGateway` enum entry —
already exists for Juspay; see Step 1).

Repo root: `/Users/sarthaksharma/StudioProjects/LokalPaymentSDK-workspace/LokalPaymentSDK`
Demo host: `/Users/sarthaksharma/StudioProjects/LokalPaymentSDK-workspace/LokalPaymentSDKDemo`

### Runtime flow
```
host backend → create-order JSON
   ↓ (host parses; the SDK never makes or parses this call — rulebook #4)
PaymentOrder(gateway: PaymentGateway, gatewayConfig: JsonObject)
   ↓
LokalPaymentSdk.pay(order)                    ← always the same host call
   ↓ routes by order.gateway to the registered handler
JuspaySdk.pay(gatewayConfig: JsonObject)      ← your handler, self-registered
   ↓ decode config → drive HyperSDK → normalize event stream
Flow<PaymentResult>  (exactly one terminal Success/Cancelled/Failure)
   ↓ core wraps it
Flow<LokalPaymentResult>  (adds the resolved gateway) → back to host
```

### The core types you implement against (all in `:shared`, package `com.getlokalapp.paymentsdk`)

`PaymentGatewayHandler.kt` — the interface your SDK class implements:
```kotlin
interface PaymentGatewayHandler {
    val gateway: PaymentGateway
    fun pay(gatewayConfig: JsonObject): Flow<PaymentResult>
    fun dispose() { LokalPaymentSdk.unregister(this) }   // default: just unregisters
}
```

`LokalPaymentSdk.kt` — an `object` registry + entry point. **Never edit it.**
`register(handler)` is called from your handler's `init{}`; `pay(order)` looks up
`handlers[order.gateway]` and returns `Failure(code="unsupported_gateway")` (never
throws) if none is registered.

`model/PaymentGateway.kt` — routing key. Already contains `JUSPAY(4)` (the `4`
mirrors the backend's gateway number — do not change it).

`model/PaymentResult.kt` — what your `pay()` emits:
```kotlin
enum class CancelReason { USER_DISMISSED, UNKNOWN }
data class PaymentError(val code: String?, val message: String)
sealed class PaymentResult {
    data class Success(val paymentId: String, val orderId: String?, val signature: String) : PaymentResult()
    data class Cancelled(val reason: CancelReason) : PaymentResult()
    data class Failure(val error: PaymentError) : PaymentResult()
}
// core wraps yours into LokalPaymentResult(gateway, result) — you never build that.
```

---

## 2. Decisions locked (do not re-litigate)

| # | Decision | Value |
|---|----------|-------|
| D1 | Juspay product | **HyperSDK / HyperCheckout** (`in.juspay:hypersdk`, plugin `hypersdk.plugin`) |
| D2 | Platform scope | **Multiplatform — real Android AND real iOS.** (Note: the matrimony reference is Android-only with an iOS no-op; iOS here is *new work with no reference* — see Risk R1.) |
| D3 | `gateway_config` shape | Backend sends a **ready-made HyperSDK `process` payload** — opaque passthrough, decode only outer wrapper, never inspect/reshape (rulebook #5) |
| D4 | Native SDK packaging | **Host app applies the Juspay `hypersdk` Gradle plugin**; the `:juspay` module compiles against HyperSDK APIs via **`compileOnly`** so it stays generic across hosts (see Risk R2) |
| D5 | clientId + init payload | **Host drives `initiate()`.** `JuspaySdk` exposes `initiate(initPayload)`; the host calls it from its bootstrap. **Android:** this only caches the payload — no `onResume()` re-forwarding needed (D10, a fresh engine boots per payment). **iOS:** the engine is longer-lived, so a production host should still re-call it from `onResume()`. `clientId` is a build-time Gradle-plugin value owned by the host (Android) / a `JuspayPlatformHandle` constructor param (iOS, R5). |
| D6 | Success status set | **`charged`, `authorizing`, `pending_vbv` → Success** (matches matrimony); `backpressed`, `user_aborted` → Cancelled; everything else → Failure |
| D7 | `signature` in Success | **Empty string `""`** — Juspay's SDK callback returns no signature; the host validates server-side (rulebook #4). `paymentId = epgTxnId`, `orderId = orderId`. |
| D8 | Back-press forwarding to the *host* | **Not implemented — no host obligation on either platform.** Confirmed dead code: matrimony's `AndroidJuspayPaymentClient.onBackPressed()` exists but is never called from its `MainActivity` (only `onResume()` is wired). The iOS HyperSDK Objective-C API has no back-press method at all. `JuspaySdk`/`JuspayClient` expose no `onBackPressed()` to the host. (Internally, D10's `JuspayActivity` *does* forward to `HyperServiceHolder.onBackPressed()` — but that's the SDK's own proxy Activity, not a host obligation.) |
| D9 | iOS pod build gate | **Set `SKIP_HYPERSDK_VALIDATION=true`** (via `launchctl setenv` in local/CI shells, or the CocoaPods pod's documented mechanism) when compiling `:juspay` generically. The `HyperSDK` pod's own `[CP-User] Validate Mandatory Files` script phase fails without it — it expects merchant assets from a client-specific `MerchantConfig.txt` + `Fuse.rb` `post_install` step that only the host's real Podfile runs. Confirmed via a live cinterop spike: with the flag set, `iosSimulatorArm64` cinterop against the real pod succeeds and produces a working `cocoapods.HyperSDK` package; without it, the synthetic Xcode build fails at that script phase. The host's real app build still runs the real asset pipeline unaffected. |
| D10 | Android: SDK-owned proxy Activity (revised) | **`JuspayActivity`** (internal, mirrors `RazorpayCheckoutActivity`'s bridge pattern) satisfies `HyperServiceHolder`'s `FragmentActivity` requirement — the host's own Activity is never touched or cast. `JuspayPlatformHandle`'s Android `actual` only needs a plain `Activity` to call `startActivity()` on. Each `pay()` launches a fresh `JuspayActivity`, which boots its own `HyperServiceHolder`, calls `initiate()` then `process()`, delivers the result, and finishes — `AndroidJuspayClient.initiate()` now just caches the payload. Trade-off: a fresh-initiate latency cost per payment (vs. matrimony's persistent-holder design) in exchange for full host encapsulation and dropping the `onResume()` obligation entirely on Android. iOS is unaffected (no `FragmentActivity`-equivalent constraint exists there). |
| D11 | Native loading indicator | Since neither `JuspayActivity` (Android) nor the host's given `UIViewController` (iOS) shows anything until HyperSDK finishes initiating, both platforms now show a plain **native** loader (no Compose — rulebook #2; no SwiftUI on iOS either, since Kotlin/Native cinterop doesn't bridge it) from `initiate()` until the `hide_loader` event: Android — a `ProgressBar` in a `FrameLayout` via `JuspayActivity.showLoader()`/`hideLoader()`; iOS — a `UIActivityIndicatorView(activityIndicatorStyle = UIActivityIndicatorViewStyleLarge)` added as a subview directly on `handle.viewController.view` via `IOSJuspayClient.showLoader()`/`hideLoader()`. |

---

## 3. Reference material map (read these files first)

### In this SDK repo — the templates to copy
The canonical **multiplatform** gateway is `:razorpay-checkout`. Copy its shape.

| File | What it teaches |
|------|-----------------|
| `razorpay-checkout/build.gradle.kts` | multiplatform module Gradle: KMP + android + cocoapods + `maven-publish`, `api(project(":shared"))`, native dep as `implementation` |
| `razorpay-checkout/src/commonMain/.../RazorpayCheckoutSdk.kt` | the `PaymentGatewayHandler` + `callbackFlow` + `awaitClose` shape |
| `razorpay-checkout/src/commonMain/.../RazorpayCheckoutConfig.kt` | opaque-config decoder with lenient JSON (`ignoreUnknownKeys`) |
| `razorpay-checkout/src/commonMain/.../RazorpayResultMapper.kt` | cancel-vs-failure classification, one layer up from the client |
| `razorpay-checkout/src/commonMain/.../RazorpayCheckoutClient.kt` | `expect fun createClient()` + internal listener interfaces |
| `razorpay-checkout/src/androidMain/.../AndroidRazorpayCheckoutClient.kt` | android `actual` |
| `razorpay-checkout/src/androidMain/.../JsonObjectConversions.kt` | **COPY nearly verbatim** — `JsonObject.toOrgJson()` (kotlinx → `org.json`) |
| `razorpay-checkout/src/iosMain/.../IOSRazorpayCheckoutClient.kt` | iOS `actual` via cocoapods interop (delegate pattern, `objcnames.classes.UIViewController` cast) |
| `razorpay-checkout/src/iosMain/.../JsonObjectConversions.kt` | **COPY nearly verbatim** — `JsonObject.toPlainMap()` (kotlinx → NSDictionary) |
| `razorpay-checkout/src/androidMain/.../RazorpayCheckoutActivity.kt` + `PaymentPresenter.*` | the proxy-Activity pattern (Juspay does **not** use a proxy — see §4; kept here as contrast) |
| `shared/src/commonMain/.../{LokalPaymentSdk,PaymentGatewayHandler}.kt`, `model/*` | the core contract (read-only) |
| `LokalPaymentSDKDemo/composeApp/.../App.kt`, `PaymentPresenter.kt`, `composeApp/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` | how a host consumes a published module + Compose glue |

### In matrimony-kmp — the working **Android** Juspay reference
Path: `/Users/sarthaksharma/StudioProjects/matrimony-kmp`
(**"matrimony" always means `matrimony-kmp`, never `matrimony-app`.**)

| File | What it teaches |
|------|-----------------|
| `composeApp/src/commonMain/.../core/payments/juspay/JuspayPaymentClient.kt` | the client interface, `JuspayPaymentData`, `JuspayConstants` (event/status strings), result-listener interface |
| `composeApp/src/androidMain/.../core/payments/juspay/AndroidJuspayPaymentClient.kt` | **the crux** — `HyperServiceHolder`, `HyperPaymentsCallbackAdapter.onEvent`, initiate→process handshake, event→result mapping, DCL for the holder. Has an `onBackPressed()` method but it's dead code — matrimony's `MainActivity` never calls it (D8) — don't port it. **Strip its Koin/DataStore/Firebase coupling** for the SDK. |
| `composeApp/src/iosMain/.../core/payments/juspay/IOSJuspayPaymentClient.kt` | iOS **no-op** (matrimony didn't do iOS) — so you have **no reference** for D2's real-iOS goal |
| `composeApp/src/androidMain/.../MainActivity.kt` | host lifecycle wiring: constructs client with `activityProvider = { this }`, calls `juspayClient.onResume()` from `onResume()`; init payload flows from bootstrap |
| `composeApp/src/androidMain/.../core/util/AndroidUtil.kt` | `JsonObject.asJSONObject()` and `Map.toJSONObject()` helpers (equivalent to Razorpay's `toOrgJson`) |
| `build.gradle.kts`, `settings.gradle.kts`, `composeApp/build.gradle.kts`, `gradle/libs.versions.toml` | the Juspay Maven repo + plugin classpath + `hyperSdkPlugin { clientId; sdkVersion }` block |

Key facts extracted from matrimony (verify versions still current when you build):
- Juspay Maven repo: `https://maven.juspay.in/jp-build-packages/hyper-sdk/` (add to `pluginManagement`, `dependencyResolutionManagement`, and `buildscript` repos as needed).
- Gradle plugin: id `hypersdk.plugin`, version `2.0.6`.
- `hyperSdkPlugin { clientId = "lokalmatrimony"; sdkVersion = "2.2.8-rc.01" }` — clientId is **app-specific** and set at build time.
- Android classes used: `in.juspay.hyperinteg.HyperServiceHolder`,
  `in.juspay.hypersdk.data.JuspayResponseHandler`,
  `in.juspay.hypersdk.ui.HyperPaymentsCallbackAdapter`.
- Event strings (lowercase): `initiate_result`, `hide_loader`, `process_result`;
  statuses: `charged`, `authorizing`, `pending_vbv`, `backpressed`, `user_aborted`.
- Success payload fields: `orderId`, `epgTxnId` (→ our `paymentId`), `status`.

---

## 4. Juspay HyperSDK mental model (why it's not shaped like Razorpay)

Razorpay Checkout is *open-once-get-one-callback*. HyperSDK is different, and the
design below exists to absorb these differences:

1. **Two payloads, two phases.**
   - **init payload** — from the host's backend *bootstrap/app-config* (not per-order).
     Used once for `HyperServices.initiate(...)`. Must be **re-initiated on resume**.
   - **process payload** — per-order, arrives as our opaque `gateway_config`. Used
     for `HyperServices.process(...)` to actually run a payment.
2. **Long-lived, stateful instance — on iOS.** One `HyperServices` per surface,
   initiated once, reused for every `process()`. (Contrast: Razorpay makes a fresh
   client per `pay()`.) → iOS's `JuspaySdk`/`IOSJuspayClient` holds **one** client
   instance for its lifetime.
   **On Android this no longer holds** (see D10): each `pay()` boots a fresh
   `HyperServiceHolder` inside the SDK's own proxy Activity, so Android's
   `initiate()` only caches the payload rather than driving a persistent engine.
3. **Event stream, not a listener pair.** Results arrive via `onEvent(json, …)`
   with an `event` field. Only `process_result` is terminal for a payment;
   `initiate_result`/`hide_loader` are lifecycle/UI events. → the client maps the
   stream down to exactly one `PaymentResult` per `pay()`.
4. **Android: SDK-owned proxy Activity (D10, revised from the original design).**
   `HyperServiceHolder` needs *some* `FragmentActivity` — unlike Razorpay's proxy
   (which exists to satisfy an interface the *calling* Activity must implement),
   there's no interface requirement here, so the original design just had the host
   pass its own `FragmentActivity`. Revised: the SDK now owns `JuspayActivity`
   (mirrors `RazorpayCheckoutActivity`'s bridge pattern) so the **host's Activity
   is never touched or cast to FragmentActivity at all** — `JuspayPlatformHandle`
   only needs a plain `Activity` to call `startActivity()` on. This also means no
   `onResume()` re-forwarding obligation on Android (a fresh engine boots every
   payment) — trading a fresh-initiate latency cost per payment (which matrimony's
   persistent-holder design avoided) for full host encapsulation (rulebook #8 now
   holds without qualification on Android). HyperSDK manages its own internal
   navigation and reports a terminal `backpressed` status via `process_result`;
   `JuspayActivity.onBackPressed()` still forwards to `HyperServiceHolder` (real,
   internal SDK plumbing — no host involvement) in case it wants to intercept a
   step within its own flow. iOS is unchanged: no `FragmentActivity`-equivalent
   constraint exists there (any `UIViewController` works), so the original
   host-passes-its-context design stands, and re-`initiate()` on resume is still
   worth doing for its longer-lived engine.

### Host-facing surface (what `JuspaySdk` exposes beyond `PaymentGatewayHandler`)
```kotlin
class JuspaySdk(handle: JuspayPlatformHandle) : PaymentGatewayHandler {
    override val gateway = PaymentGateway.JUSPAY
    override fun pay(gatewayConfig: JsonObject): Flow<PaymentResult>   // → process()
    fun initiate(initPayload: JsonObject)   // host: call from bootstrap (+ on resume, iOS only — D10)
    override fun dispose()                  // unregister + tear down the client
}
```
`JuspayPlatformHandle`'s Android `actual` wraps `() -> Activity` — a **plain**
Activity, not `FragmentActivity` (D10: the SDK's own `JuspayActivity` satisfies
that requirement internally). iOS wraps a `UIViewController` + `tenantId`/`clientId`
(R5). Model the platform handle the way `:razorpay-checkout` models
`PaymentPresenter`, but as your **own** type — do NOT reuse
`:razorpay-checkout`'s `PaymentPresenter` (that would make leaf depend on leaf —
rulebook #3). (The `PaymentGatewayHandler` interface only mandates `pay`/`dispose`;
extra methods like `initiate` are allowed — the interface's own kdoc says platform
setup is a concrete-class concern.)

---

## 5. Target module structure

```
juspay/
  build.gradle.kts
  src/
    commonMain/kotlin/com/getlokalapp/paymentsdk/juspay/
      JuspayConfig.kt          # opaque process-payload decoder
      JuspayConstants.kt       # event + status string constants
      JuspayResultMapper.kt    # status → PaymentResult (D6/D7)
      JuspayClient.kt          # expect client + internal listener/data interfaces
      JuspaySdk.kt             # PaymentGatewayHandler + initiate()
      JuspayPlatformHandle.kt  # expect class (Activity on Android, UIViewController on iOS)
    androidMain/
      AndroidManifest.xml              # registers JuspayActivity (D10)
      kotlin/com/getlokalapp/paymentsdk/juspay/
        AndroidJuspayClient.kt           # D10: caches init payload, launches JuspayActivity per pay()
        JuspayActivity.kt                # D10: SDK-owned proxy — owns HyperServiceHolder + FragmentActivity
        JuspayPlatformHandle.android.kt  # actual class = plain Activity holder (not FragmentActivity — D10)
    iosMain/kotlin/com/getlokalapp/paymentsdk/juspay/
      IOSJuspayClient.kt               # HyperSDK pod — real API confirmed, R1 resolved
      JuspayPlatformHandle.ios.kt      # actual class = UIViewController + tenantId/clientId holder
    # No commonTest — Step 11's tests were dropped for now (by request).
```
`toOrgJson()`/`toPlainMap()`/`lenientJson` are **not** duplicated per-module anymore —
they live in `:shared` (`shared/src/{commonMain,androidMain,iosMain}/.../json/`) and
every gateway module (`razorpay-checkout`, `razorpay-customui`, `juspay`) imports
from there. This also fixed a real bug found via a live Juspay `jp_003` error: the
old per-module `toOrgJsonPrimitive()` checked `longOrNull` before `isString`, so a
numeric-looking JSON *string* (e.g. `"customerId": "308184"`) silently became a
`Long` — fixed once in `:shared`, inherited by all three modules.
Package is **`com.getlokalapp.paymentsdk.juspay`** — its own package (not the
`…razorpay` one) so top-level file names can't collide at the JVM class level for a
host depending on multiple gateways (rulebook #9).

---

## 6. Step-by-step

### Step 0 — De-risking spikes (DO THIS FIRST; report back if either fails)

**Spike A — Android compile classpath (Risk R2).** We want the module to compile
against HyperSDK classes without forcing the plugin on every consumer.
1. Add the Juspay Maven repo to the SDK's `settings.gradle.kts`
   (`dependencyResolutionManagement.repositories`) and to `pluginManagement`.
2. Determine the exact artifact coordinates the `hypersdk` plugin resolves. In
   matrimony run `./gradlew :composeApp:dependencies --configuration debugRuntimeClasspath | grep -i juspay`
   (or inspect the plugin) to find the real coordinates + version behind
   `HyperServiceHolder` (expect something like `in.juspay:hyperinteg:<v>` and
   `in.juspay:hypersdk:<v>` at `sdkVersion = 2.2.8-rc.01`).
3. In a scratch build, declare those as `compileOnly` in `androidMain` and confirm
   `HyperServiceHolder`, `HyperPaymentsCallbackAdapter`, `JuspayResponseHandler`
   resolve at compile time.
4. Confirm the module builds **without** applying `hypersdk.plugin` itself.
   - **If compileOnly resolves the classes** → proceed with D4 as designed.
   - **If the classes are only available via the plugin** (i.e. the plugin injects
     them, no plain artifact exists) → stop; the fallback is either (a) the module
     applies the plugin with a host-supplied `clientId` (makes the published module
     app-specific), or (b) `compileOnly` against a thin stub. Report findings and
     recommend before continuing.

**Spike B — iOS HyperSDK pod (Risk R1).** There is **no reference** for this.
1. Confirm the CocoaPod name/version (`HyperSDK`) on cocoapods.org and Juspay's iOS
   docs (`https://docs.juspay.io`). Capture the real API for:
   - creating the services object,
   - `initiate(...)` (takes a presenting `UIViewController`, a payload dict, and a
     callback/delegate),
   - `process(...)`,
   - back-press/dismiss handling and the event/result callback shape (does iOS emit
     the same `process_result`/status strings as Android?).
2. Build a throwaway `iosSimulatorArm64` target that adds `pod("HyperSDK")` and
   references one symbol, to confirm cinterop generates a usable `cocoapods.HyperSDK`
   package.
   - **If the pod interops cleanly** → implement Step 10 for real.
   - **If it does not** (or the iOS API can't be driven from Kotlin/Native cleanly)
     → stop and report. The documented fallback is to ship iOS as a **stub** (mirror
     `razorpay-customui`'s `RazorpayCustomUiIosStub`) and revisit — but D2 asked
     for real iOS, so surface this rather than silently stubbing.

### Step 1 — `PaymentGateway` enum — SKIP
`JUSPAY(4)` already exists in `shared/.../model/PaymentGateway.kt`. Make no `:shared`
edits. (This is the only `:shared` edit a gateway is ever allowed, and it's done.)

### Step 2 — Gradle wiring (settings + version catalog + Juspay repo)
`settings.gradle.kts` (SDK root):
```kotlin
// SKETCH
// add Juspay repo so the compileOnly HyperSDK artifact resolves at module build time
dependencyResolutionManagement {
    repositories {
        google { /* existing content filters */ }
        mavenCentral()
        maven("https://maven.juspay.in/jp-build-packages/hyper-sdk/")   // NEW
    }
}
// ...
include(":shared")
include(":razorpay-checkout")
include(":razorpay-customui")
include(":juspay")   // NEW
```
`gradle/libs.versions.toml` (SDK) — add versions + libraries for the HyperSDK
artifacts confirmed in Spike A, e.g.:
```toml
# SKETCH — use the coordinates/version you confirmed in Spike A
[versions]
juspay-hypersdk = "2.2.8-rc.01"
[libraries]
juspay-hyperinteg = { module = "in.juspay:hyperinteg", version.ref = "juspay-hypersdk" }
juspay-hypersdk   = { module = "in.juspay:hypersdk",   version.ref = "juspay-hypersdk" }
```

### Step 3 — `juspay/build.gradle.kts`
Copy `razorpay-checkout/build.gradle.kts` and change:
```kotlin
// SKETCH — deltas from the razorpay-checkout copy
group = "com.getlokalapp.paymentsdk"
version = "0.0.1"

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    androidLibrary {
        namespace = "com.getlokalapp.paymentsdk.juspay"   // UNIQUE (rulebook #9)
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        withHostTest {}
    }
    iosX64(); iosArm64(); iosSimulatorArm64()
    cocoapods {
        version = "0.0.1"
        summary = "Lokal Payment SDK - Juspay HyperCheckout"
        homepage = "https://github.com/getlokalapp/LokalPaymentSDK"
        ios.deploymentTarget = "16.0"
        name = "Juspay"                       // UNIQUE
        framework { baseName = "Juspay"; isStatic = true }   // UNIQUE
        pod("HyperSDK") {                     // confirm name/version in Spike B
            version = "<confirmed>"
            // extraOpts / moduleName as Spike B dictates
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))            // api — a :shared type is in pay()'s signature
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // compileOnly (D4): host applies the hypersdk plugin which supplies these at runtime;
            // consumers who don't use Juspay must not get these on their classpath.
            compileOnly(libs.juspay.hyperinteg)
            compileOnly(libs.juspay.hypersdk)
            implementation("androidx.fragment:fragment:<version>")  // for FragmentActivity handle
        }
    }
}
```
Keep the `maven-publish` plugin, the `serialization` plugin, and the
`-Xexpect-actual-classes` arg (all present in the razorpay copy). **Do not** add a
`hyperSdkPlugin {}` block here (D4 — that belongs to the host).

### Step 4 — `JuspayConfig.kt` (commonMain) — opaque process-payload decoder
Model on `RazorpayCheckoutConfig.kt`. **R3 resolved:** a real `gateway_config`
captured from a matrimony sandbox flow confirms this wrapper shape exactly — no
decoder changes needed. Implemented as:
```kotlin
package com.getlokalapp.paymentsdk.juspay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class JuspayConfig(
    // The ready-made HyperSDK `process` payload, opaque — handed straight to process().
    @SerialName("sdk_payload") val sdkPayload: JsonObject,
    // Optional: any order id the host echoes back (e.g. generated_order_id). Opaque.
    @SerialName("generated_order_id") val generatedOrderId: String? = null,
)

private val lenientJson = Json { ignoreUnknownKeys = true }   // tolerate sibling fields (rulebook #5)

internal fun JsonObject.toJuspayConfig(): JuspayConfig =
    lenientJson.decodeFromJsonElement(JuspayConfig.serializer(), this)
```
The real sample's `sdk_payload` carries extra fields alongside `requestId`/`service`
(`payload`, `currTime`, `xRoutingId`) — all opaque, all captured as-is since
`sdkPayload` is typed as a raw `JsonObject`, not a nested data class (rulebook #5:
never inspect/reshape the inner payload).

### Step 5 — `JuspayConstants.kt` + `JuspayResultMapper.kt` (commonMain)
```kotlin
// COPY constants from matrimony JuspayPaymentClient.kt (values verified there)
package com.getlokalapp.paymentsdk.juspay

internal object JuspayEvents {
    const val INITIATE_RESULT = "initiate_result"
    const val HIDE_LOADER = "hide_loader"
    const val PROCESS_RESULT = "process_result"
}
internal object JuspayStatus {
    const val CHARGED = "charged"
    const val AUTHORIZING = "authorizing"
    const val PENDING_VBV = "pending_vbv"
    const val BACKPRESSED = "backpressed"
    const val USER_ABORTED = "user_aborted"
}
```
```kotlin
// SKETCH — the D6/D7 classification, one layer up from the platform client
package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentResult

/** Raw, already-extracted fields from a Juspay process_result event. */
internal data class JuspayResultData(
    val status: String,
    val orderId: String?,
    val txnId: String?,        // epgTxnId
    val errorCode: String?,
    val errorMessage: String?,
)

internal fun juspayResultToPaymentResult(data: JuspayResultData): PaymentResult =
    when (data.status.lowercase()) {
        JuspayStatus.CHARGED, JuspayStatus.AUTHORIZING, JuspayStatus.PENDING_VBV ->
            PaymentResult.Success(
                paymentId = data.txnId.orEmpty(),   // D7: paymentId = epgTxnId
                orderId = data.orderId,
                signature = "",                      // D7: Juspay returns no signature
            )
        JuspayStatus.BACKPRESSED, JuspayStatus.USER_ABORTED ->
            PaymentResult.Cancelled(CancelReason.USER_DISMISSED)
        else ->
            PaymentResult.Failure(
                PaymentError(
                    code = data.errorCode ?: data.status,
                    message = data.errorMessage ?: "Juspay payment failed (status=${data.status})",
                ),
            )
    }
```

### Step 6 — `JuspayClient.kt` + `JuspayPlatformHandle.kt` (commonMain)
```kotlin
// SKETCH
package com.getlokalapp.paymentsdk.juspay

import kotlinx.serialization.json.JsonObject

/** Absorbs Juspay's callback so the host never implements a Juspay interface (rulebook #8, partial). */
internal interface JuspayResultListener {
    fun onResult(data: JuspayResultData)   // terminal: maps to exactly one PaymentResult
}

internal interface JuspayClient {
    val isInitialised: Boolean
    fun initiate(initPayload: JsonObject)                 // idempotent; safe to call again on resume
    fun process(processPayload: JsonObject)               // runs a payment (after initiate)
    fun setResultListener(listener: JuspayResultListener?)
    fun dispose()
}

internal expect fun createJuspayClient(handle: JuspayPlatformHandle): JuspayClient
```
```kotlin
// your own handle type; do NOT reuse :razorpay-checkout's PaymentPresenter
package com.getlokalapp.paymentsdk.juspay
expect class JuspayPlatformHandle   // android actual wraps () -> Activity (D10 — plain Activity, not FragmentActivity); ios actual wraps UIViewController + tenantId/clientId
```

### Step 7 — `JuspaySdk.kt` (commonMain) — the handler + host surface
```kotlin
// SKETCH
package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject

class JuspaySdk(handle: JuspayPlatformHandle) : PaymentGatewayHandler {

    override val gateway: PaymentGateway = PaymentGateway.JUSPAY

    // iOS: ONE long-lived client (HyperServices is stateful there). Android
    // (D10): this client is a thin cache — the real HyperServiceHolder is
    // ephemeral, created fresh per pay() inside the SDK's own JuspayActivity.
    private val client: JuspayClient = createJuspayClient(handle)

    init { LokalPaymentSdk.register(this) }   // the whole registration mechanism

    /** Host calls this from bootstrap. Android: caches the payload only. iOS: drives a persistent engine — also re-call on resume. */
    fun initiate(initPayload: JsonObject) = client.initiate(initPayload)

    override fun pay(gatewayConfig: JsonObject): Flow<PaymentResult> = callbackFlow {
        val config = gatewayConfig.toJuspayConfig()
        client.setResultListener(object : JuspayResultListener {
            override fun onResult(data: JuspayResultData) {
                trySend(juspayResultToPaymentResult(data))   // exactly one terminal result
                close()
            }
        })
        // If no init payload has been cached at all, the actual emits
        // Failure("juspay_not_initiated") and returns without launching anything.
        client.process(config.sdkPayload)
        awaitClose { client.setResultListener(null) }
    }

    override fun dispose() {
        client.dispose()
        super.dispose()   // unregisters from LokalPaymentSdk
    }
}
```

### Step 8 — `AndroidJuspayClient.kt` + `JuspayActivity.kt` (androidMain) — the crux, revised (D10)

**Superseded the original matrimony-ported design** (one long-lived `AndroidJuspayClient`
holding a persistent `HyperServiceHolder`, with the host supplying its own
`FragmentActivity`). D10 replaced it with an SDK-owned proxy Activity, mirroring
`RazorpayCheckoutActivity`'s bridge pattern:

- **`AndroidJuspayClient.kt`** — a thin cache. `initiate(initPayload)` just stores
  the payload. `process(processPayload)` parks a `PendingJuspayPayment`
  (init payload + process payload + listener) in a `JuspayActivityBridge` object
  and launches `JuspayActivity` via `activityProvider().startActivity(...)` — no
  `FragmentActivity` cast anywhere in this file.
- **`JuspayActivity.kt`** (new, internal) — an `androidx.fragment.app.FragmentActivity`
  the SDK owns and registers itself (see manifest below). On `onCreate`, picks up
  the pending request, constructs its own fresh `HyperServiceHolder(this)`,
  `setCallback(...)`, and `initiate()`s. The callback's `initiate_result` handler
  calls `process()` on that same fresh holder (the matrimony handshake is now
  scoped to one Activity's lifetime, not a static singleton); `process_result`
  delivers to the listener and `finish()`es. `onBackPressed()` forwards to
  `holder.onBackPressed()` (real SDK-internal plumbing, not a host obligation —
  D8). `onDestroy()` calls `holder.terminate()`.
- **`AndroidManifest.xml`** (new, `juspay/src/androidMain/AndroidManifest.xml`) —
  registers `JuspayActivity` (`exported="false"`), mirroring
  `razorpay-checkout`'s manifest for `RazorpayCheckoutActivity`. Merged into the
  host app automatically — no host manifest changes.
- **Koin/DataStore/Firebase coupling removed** (as originally planned) — the init
  payload lives in `AndroidJuspayClient`'s in-memory field, not DataStore.

Confirmed real via a full build: `HyperServiceHolder.terminate()`,
`isInitialised()`, `onBackPressed()`, `setCallback(HyperPaymentsCallback)` all
resolve as documented (decompiled `hyperinteg`'s `classes.jar`, R2). **Still
unconfirmed:** the exact `process_result` success-field names (`epgTxnId`,
`orderId`) — R3's real sample only covers the `initiate`/`process` *request*
shape, not a captured *response*. Verify against a live sandbox
transaction.

### Step 9 — JSON conversion helpers — SUPERSEDED, now shared via `:shared`
**Do not copy `JsonObjectConversions.kt` per-module.** After all three gateway
modules ended up with the same copy-pasted `toOrgJson()`/`toPlainMap()`/`lenientJson`
(and the same latent bug — see below), they were moved into `:shared` once, for
good:
- `shared/src/commonMain/.../json/LenientJson.kt` — `val lenientJson = Json { ignoreUnknownKeys = true }`
- `shared/src/androidMain/.../json/JsonConversions.android.kt` — `JsonObject.toOrgJson(): org.json.JSONObject`
- `shared/src/iosMain/.../json/JsonConversions.ios.kt` — `JsonObject.toPlainMap(): Map<Any?, Any?>`

`:juspay` (and `razorpay-checkout`/`razorpay-customui`) just `import
com.getlokalapp.paymentsdk.json.toOrgJson` / `.toPlainMap` / `.lenientJson` — all
three already depend on `:shared` via `api(project(":shared"))`, so no new
dependency was needed. This sidesteps rulebook #9's original filename-collision
concern entirely (one compiled class in `:shared`, not N copies in sibling leaf
modules) rather than working around it with unique-but-duplicated files.

**Bug fixed while consolidating:** `JsonPrimitive.longOrNull`/`doubleOrNull`/
`booleanOrNull` parse `content` regardless of whether the original JSON literal
was quoted, so the old `toOrgJsonPrimitive()` (which checked `longOrNull` before
`isString`) silently turned a numeric-looking JSON *string* (e.g. `"customerId":
"308184"`) into a `Long`. This caused a real Juspay `jp_003` "type mismatch,
expected string found number" error. Fixed by checking `isString` first — now the
single source of truth for all three modules.

### Step 10 — `IOSJuspayClient.kt` (iosMain) — risk resolved by Spike B, one open question remains

No reference exists in matrimony, but Spike B confirmed the real API by inspecting the
actual `HyperSDK.xcframework` header (`Hyper.h`) and running a live cinterop build. It is
**plain Objective-C** (not delegate-based) — `HyperServices : Hyper` exposes:
```objc
- (instancetype)initWithTenantId:(NSString *)tenantId clientId:(NSString *)clientId;
- (Boolean)isInitialised;
- (void)initiate:(UIViewController *)vc payload:(NSDictionary *)payload callback:(HyperSDKCallback)callback;
- (void)process:(NSDictionary *)processPayload;
- (void)terminate;
```
`HyperSDKCallback` is `void (^)(NSDictionary<NSString*,id>* _Nullable data)` — a plain
callback block, invoked repeatedly with event dictionaries (`event` field, mirroring
Android's `initiate_result`/`hide_loader`/`process_result`). There is **no back-press
method on iOS at all** (D8) — confirms no forwarding is needed on either platform.

**R5, resolved:** `HyperServices` is constructed with an explicit `tenantId`/
`clientId` pair — unlike Android (D10: the host-visible surface needs no clientId
at all; `hyperSdkPlugin { clientId = ... }` is a host `:androidApp` Gradle
concern), there's no iOS equivalent of the Gradle plugin to inject this at build
time. Resolved as option (a): `JuspayPlatformHandle`'s iOS `actual` constructor
takes `tenantId`/`clientId` params, defaulting to `"juspayindia"` / matrimony's
real registered clientId (borrowed with explicit user permission so the module
exercises the real flow rather than an invented placeholder) — swap in the
host's own clientId once one is issued.

```kotlin
// SKETCH — symbols confirmed real via Spike B; tenantId/clientId sourcing still open (see above)
package com.getlokalapp.paymentsdk.juspay

import cocoapods.HyperSDK.HyperServices
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.JsonObject
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class)
internal actual fun createJuspayClient(handle: JuspayPlatformHandle): JuspayClient =
    IOSJuspayClient(handle.viewController, handle.tenantId, handle.clientId)   // tenantId/clientId sourcing: see open question above

@OptIn(ExperimentalForeignApi::class)
internal class IOSJuspayClient(
    private val viewController: UIViewController,
    tenantId: String,
    clientId: String,
) : JuspayClient {
    private val services = HyperServices(tenantId = tenantId, clientId = clientId)
    private var listener: JuspayResultListener? = null

    override val isInitialised: Boolean get() = services.isInitialised()

    override fun initiate(initPayload: JsonObject) {
        services.initiate(viewController, initPayload.toPlainMap()) { data ->
            // map data["event"] (initiate_result/hide_loader/process_result) the same
            // way AndroidJuspayClient does; only process_result is terminal
        }
    }

    override fun process(processPayload: JsonObject) {
        services.process(processPayload.toPlainMap())
    }

    override fun setResultListener(listener: JuspayResultListener?) { this.listener = listener }
    override fun dispose() { services.terminate() }
}
```
Follow the `IOSRazorpayCheckoutClient.kt` conventions for the cinterop OptIn and
Objective-C interop. **Build note (D9):** compiling `:juspay`'s iOS targets requires
`SKIP_HYPERSDK_VALIDATION=true` set in the environment (the pod's own asset-validation
script phase fails otherwise) — document this in the module README and CI config.

### Step 11 — Unit tests (commonTest) — SKIPPED for now (by request)
`JuspayConfigTest`/`JuspayResultMapperTest` were written and passing (9 tests, both
Android and iOS) at one point in this build, then removed at the user's request — no
`commonTest` source set currently exists for `:juspay`. Add back if/when tests are
wanted; the shapes below are what they covered:
- `JuspayConfigTest` — decodes a representative `gateway_config` (use the real R3
  sample) into `JuspayConfig`; tolerates unknown sibling fields.
- `JuspayResultMapperTest` — table test over statuses: `charged`/`authorizing`/
  `pending_vbv` → `Success` (paymentId=epgTxnId, signature==""), `backpressed`/
  `user_aborted` → `Cancelled(USER_DISMISSED)`, an arbitrary failure status →
  `Failure` with code/message. (These are pure-common, no Android/iOS deps.)

### Step 12 — Publish + host wiring (in `LokalPaymentSDKDemo`) — implemented, R4 resolved
The SDK code needs **zero** host-dispatch edits, and — after D10 — **zero**
host Activity-type edits either. Host steps, as actually done:
1. **Publish:** `./gradlew :shared:publishToMavenLocal :razorpay-checkout:publishToMavenLocal :razorpay-customui:publishToMavenLocal :juspay:publishToMavenLocal`.
2. **Host Gradle:**
   - `gradle/libs.versions.toml`: `lokalpaymentsdk-juspay = { module = "com.getlokalapp.paymentsdk:juspay", version.ref = "lokalPaymentSdk" }`, plus a **versionless** plugin alias `lokalpaymentsdk-juspay-host = { id = "com.getlokalapp.paymentsdk.juspay-host" }`. No Juspay-related version appears anywhere in the host: the raw `hypersdk.plugin` pin (`2.0.6`) lives inside `:juspay:host-plugin` (below), and the wrapper plugin's own version is registered as a `pluginManagement` default by the `lokal-payment-settings` umbrella's Juspay contributor (below), so the umbrella settings plugin's single pin covers everything.
   - `composeApp/build.gradle.kts`: `implementation(libs.lokalpaymentsdk.juspay)`. **Does NOT** apply the plugin — confirmed it fails there (R4, resolved): `com.android.kotlin.multiplatform.library` modules don't expose a plain `implementation` configuration, which is what the underlying `hypersdk.plugin` injects into.
   - `androidApp/build.gradle.kts` — **apply `com.getlokalapp.paymentsdk.juspay-host` here instead** (a conventional `com.android.application` module has the configuration the plugin expects):
     ```kotlin
     plugins { alias(libs.plugins.lokalpaymentsdk.juspay.host) }   // versionless alias — default registered by lokal-payment-settings
     lokalJuspayHost { clientId = "lokalmatrimony" }
     ```
     `clientId` is matrimony's real, already-registered one, borrowed with explicit
     user permission — the plugin fetches merchant config from Juspay's live servers
     at Gradle *configuration* time and 403s hard on an unregistered clientId, so a
     made-up placeholder can't get past this. Swap for this host's own clientId once
     issued. `sdkVersion` is **not** host-configurable — it's fixed inside
     `JuspayHostPlugin` to whatever `:juspay` compiled against, so the runtime
     SDK a host fetches can never drift from that.
   - `settings.gradle.kts`: add `mavenLocal()` to `pluginManagement.repositories` (resolves the umbrella + its contributors) and apply `com.getlokalapp.paymentsdk.lokal-payment-settings` in the top-level `plugins {}` block. The host's `settings.gradle.kts` needs **zero** mentions of `maven.juspay.in` — the umbrella's Juspay contributor adds it to both `pluginManagement` and `dependencyResolutionManagement` on the host's behalf. This is the *only* settings-plugin id a host ever applies, regardless of how many gateways it uses.

   **`:juspay:host-plugin`** (new, added after this step was first written): a plain
   `java-gradle-plugin` module nested under `:juspay` (not a KMP/Android-library
   module — it can't be, since a Gradle plugin jar and a published KMP library are
   incompatible project shapes). It depends on `in.juspay:hypersdk.plugin:2.0.6`
   directly (the version pin now lives here, not in any host's catalog) and its
   `JuspayHostPlugin` applies `hypersdk.plugin` internally, forwarding
   `lokalJuspayHost { clientId }` into the real `HyperSdkPluginExtension`
   (`sdkVersion` is fixed inside the plugin, not host-configurable).

   **Umbrella settings plugin — `com.getlokalapp.paymentsdk.lokal-payment-settings`**
   (originally shipped as a per-gateway `:juspay:host-settings-plugin`; since folded
   into a single host-facing umbrella that mirrors the project-phase
   `lokal-payment`). The `LokalPaymentSettingsPlugin` (a `Plugin<Settings>`, applied
   from a host's `settings.gradle.kts` itself, not a module's `build.gradle.kts`)
   lives in its own module `:shared:shared-settings-plugin` and does no
   gateway-specific work: it discovers every `LokalGatewaySettingsContributor` on the
   settings classpath via `ServiceLoader` and calls `contribute(settings)`. The SPI
   lives in its own plain jar, `:settings-plugin-api` (twin of
   `:cocoapods-host-plugin-api`). It is a *separate* module from
   `:shared:shared-cocoapods-plugin` deliberately: a `Plugin<Settings>` jar lands on
   the parent (settings) classpath visible to every project, so sharing a jar with
   the `lokal-payment` project plugin would leave that plugin "already on the
   classpath with an unknown version" and unappliable with an explicit version
   (confirmed failure).

   **`:juspay:settings-contributor`** (new, replaces the old
   `:juspay:host-settings-plugin`): a plain jar — **not** a `java-gradle-plugin` — that
   ships `JuspaySettingsContributor : LokalGatewaySettingsContributor` and registers
   it in `META-INF/services`. `contribute(settings)` calls
   `settings.pluginManagement.repositories.maven(...)` and
   `settings.dependencyResolutionManagement.repositories.maven(...)` to add
   `maven.juspay.in` on the host's behalf, and registers a `pluginManagement` default
   version for the `juspay-host` project plugin id so app modules apply it
   version-free. It has **zero** dependency on `in.juspay:hypersdk.plugin` (or anything
   else juspay-specific) — otherwise loading it would itself require `maven.juspay.in`
   to already be resolvable, the exact repo it exists to add (a chicken-and-egg
   failure confirmed empirically when this was first tried as a second plugin ID
   inside `:juspay:host-plugin`'s jar; Gradle resolves a module's whole dependency
   graph as one unit regardless of which plugin ID is applied). Unlike the project-
   phase `LokalGatewayHostContributor`, settings contributors do **not** self-gate —
   the module dependency graph isn't known during settings evaluation, so they
   contribute unconditionally (an unused repo / an unapplied plugin's version pin are
   no-ops); real gating stays at the project level.

   Publish alongside the rest: `./gradlew :juspay:host-plugin:publishToMavenLocal :juspay:settings-contributor:publishToMavenLocal :settings-plugin-api:publishToMavenLocal :shared:shared-settings-plugin:publishToMavenLocal`.
   Deliberately **not** propagated to matrimony-kmp as part of this change — only
   `LokalPaymentSDKDemo` was migrated to it so far.
3. **Compose glue** (`JuspayPresenter.kt`, alongside `PaymentPresenter.kt`) — `JuspaySdk`'s
   constructor overload takes the init payload and calls `initiate` itself, so the
   composable only wires the platform handle and the dispose-on-leave lifecycle:
   ```kotlin
   @Composable
   expect fun rememberJuspayHandle(): JuspayPlatformHandle   // android actual: plain Activity (D10); ios actual: UIViewController

   @Composable
   fun rememberJuspaySdk(initPayload: JsonObject): JuspaySdk {
       val handle = rememberJuspayHandle()
       val sdk = remember(handle, initPayload) { JuspaySdk(handle, initPayload) }   // host's real bootstrap payload
       DisposableEffect(sdk) {
           onDispose { sdk.dispose() }
       }
       return sdk
   }
   ```
   The Android `actual` is just `JuspayPlatformHandle { activity }` off
   `LocalActivity.current` — **no cast, no `FragmentActivity` import** (D10).
4. **`MainActivity`** — **unchanged**, stays `ComponentActivity` (D10). No
   `onResume()` forwarding needed on Android; a production iOS host should still
   re-`initiate()` on resume for its longer-lived engine.
5. **App button** (`App.kt`): `PaymentGateway.JUSPAY in registeredGateways` button
   calling `pay(SAMPLE_JUSPAY_CREATE_ORDER_RESPONSE)` — wired with a **real**
   `gateway_config` captured from a matrimony sandbox flow (R3, resolved), not an
   invented sample.

---

## 7. Rules to obey + the Juspay-specific deviations (and why they're allowed)

**Obey (playbook §5 rulebook):**
1. Never edit `LokalPaymentSdk` dispatch / add a `when`-branch in `:shared`.
2. No Compose in any SDK module — registration is a plain `init{}`, cleanup a plain
   `dispose()`. `remember…`/`DisposableEffect` live in the host.
3. Native SDK deps stay off consumers who don't use Juspay — here via `compileOnly`
   (D4) + host-applied plugin, which is *stricter* than `implementation`.
4. The SDK never makes or parses create-order/validate calls; it returns raw fields
   and the host validates server-side (this is *why* D7's empty signature is fine).
5. `gateway_config` inner payload is opaque — decode only the wrapper, pass the
   process payload straight to `process()`; `ignoreUnknownKeys = true`.
6. Classify cancel-vs-failure in the result mapper using Juspay's own statuses (D6).
7. Emit exactly one terminal `PaymentResult` then `close()` — only `process_result`
   is terminal; guard against double-emit (`callbackFlow` + `close()` + `awaitClose`).
8. The host never implements a Juspay callback or Juspay-specific Activity type —
   `HyperPaymentsCallbackAdapter` is absorbed inside `JuspayActivity` (Android,
   D10) / a plain callback closure inside `IOSJuspayClient` (iOS). **Holds without
   qualification on Android** (revised from the original design): the SDK's own
   `JuspayActivity` satisfies `HyperServiceHolder`'s `FragmentActivity`
   requirement, so the host's Activity is never touched, cast, or forwarded from
   — no `onResume()` obligation either (D10). On iOS the host still owns the
   `UIViewController` it passes in (unavoidable — HyperSDK needs somewhere to
   present), and a production host should still re-`initiate()` on resume for its
   longer-lived engine. No back-press forwarding is needed on either platform
   (D8).
9. Unique `namespace`, cocoapods `name`/`baseName`, and file names; own package
   `…juspay`.
10. Enum number matches the backend (`JUSPAY(4)`) — already correct, untouched.

**Deviations from the Razorpay template (intentional, Juspay-specific):**
- **iOS: one long-lived client** held by `JuspaySdk` (Razorpay makes one per
  `pay()`), because `HyperServices` is stateful and initiated once.
  **Android (D10): the opposite** — a fresh `HyperServiceHolder` boots inside a
  fresh `JuspayActivity` per `pay()`, closer to Razorpay's per-payment model,
  but for a different reason (host encapsulation, not an SDK requirement).
- **`JuspaySdk` exposes `initiate()`** beyond the `PaymentGatewayHandler` interface
  (allowed — platform setup is a concrete-class concern per the interface kdoc).
- **Android: an SDK-owned proxy Activity, like Razorpay's — but for a different
  reason.** Razorpay's proxy satisfies an interface *Razorpay* requires the
  calling Activity to implement; `JuspayActivity` exists purely to satisfy
  `HyperServiceHolder`'s `FragmentActivity` type requirement without forcing the
  host's own Activity to be one (D10, revised from the original "no proxy"
  design).
- **Native SDK via a Gradle plugin, `compileOnly`** (D4), not a Maven
  `implementation` artifact.

---

## 8. Verification checklist (playbook §6)
```bash
# each module builds independently; :shared still pulls in no gateway SDK
./gradlew :shared:build :razorpay-checkout:build :razorpay-customui:build :juspay:build
./gradlew :juspay:publishToMavenLocal # then wire into LokalPaymentSDKDemo
# No :juspay:allTests — Step 11's tests were dropped for now (by request).
```
Then, in `LokalPaymentSDKDemo`:
- Confirm `PaymentGateway.JUSPAY in LokalPaymentSdk.registeredGateways()` after
  constructing `JuspaySdk`.
- Run a **real Juspay sandbox** payment end-to-end and confirm all three paths:
  **Success** (`charged`), **Cancelled** (back-press → `backpressed`/`user_aborted`),
  **Failure** (a declined/failed status). Verify `paymentId`(=epgTxnId)/`orderId`
  extraction on a live transaction.
- Confirm **re-initiate on resume** works (background the app mid-flow, return).
- **iOS:** confirm the same three paths against a live transaction — this is the
  least-proven path (R1); do not mark iOS done on a compile-only basis.

---

## 9. Open risks — resolve during the build, report if blocked

| ID | Risk | Resolution |
|----|------|-----------|
| **R1** | ~~Real iOS HyperSDK has no reference (matrimony stubbed iOS). Pod API, cinterop viability, and event/status strings unverified.~~ **RESOLVED.** `HyperSDK` is a public CocoaPod; its `.xcframework` is plain Objective-C (`Hyper.h`, `HyperServices : Hyper`) with `initiate:payload:callback:`, `process:`, `isInitialised`, `terminate`. A live `iosSimulatorArm64` cinterop build against the real pod succeeded (with `SKIP_HYPERSDK_VALIDATION=true`, see D9) and produced a working `cocoapods.HyperSDK` package containing exactly those symbols. No back-press method exists on iOS at all (D8). **Remaining open item:** `HyperServices` requires an explicit `tenantId`/`clientId` constructor pair with no Android-style implicit Gradle-plugin injection — see the open question in Step 10 for how `IOSJuspayClient` should receive these. |
| **R2** | ~~`compileOnly` + host-applied plugin may not resolve the HyperSDK classes at module compile time.~~ **RESOLVED.** Confirmed via matrimony's `:composeApp:dependencies` configure-phase output and direct `curl` against `maven.juspay.in`: `in.juspay:hypersdk`, `hyperinteg`, `hyperlottie`, `hypernfc`, `hyperqr` (all `2.2.8-rc.01`) are plain public Maven artifacts, independent of the plugin. Decompiled `hyperinteg`'s `HyperServiceHolder.class` — every method the Step 8 sketch uses (`initiate`, `process`, `terminate`, `isInitialised`, `onBackPressed` (unused, see D8), `setCallback`) is real. D4 holds as designed. |
| **R3** | ~~Exact `gateway_config` wrapper (`sdk_payload`/`generated_order_id`) unconfirmed against this host's backend.~~ **RESOLVED for the request shape** — a real `gateway_config` and a real init payload were captured from a matrimony sandbox flow and match `JuspayConfig`'s assumed wrapper exactly. **Still open:** the `process_result` *response* success-field names (`epgTxnId`/`orderId`) — no captured response sample yet, only requests. Verify against a live sandbox transaction. |
| **R4** | ~~Juspay plugin target — whether `hypersdk.plugin` applies to the KMP-library `composeApp` or must sit on `:androidApp`.~~ **RESOLVED.** It must sit on `:androidApp` (a conventional `com.android.application` module). Applying it to `:composeApp` (a `com.android.kotlin.multiplatform.library` module) fails with `Configuration with name 'implementation' not found` — the plugin injects its dependencies via a plain `implementation` configuration that KMP-library modules don't expose (they use per-source-set configs like `androidMainImplementation` instead). Confirmed via a real build of `LokalPaymentSDKDemo`. |
| **R5** | **iOS `tenantId`/`clientId` sourcing** (see R1) resolved as: `JuspayPlatformHandle`'s iOS `actual` constructor takes them as parameters with defaults (`tenantId = "juspayindia"`, `clientId` = matrimony-kmp's real, already-registered clientId, borrowed with explicit user permission so the demo/module can exercise the real Juspay flow rather than a made-up value). **Still open:** this is matrimony's merchant account, not this host's own — swap in this host's real clientId (Android: `hyperSdkPlugin { clientId = ... }` in `:androidApp`; iOS: the `clientId` param at `JuspayPlatformHandle` construction) once one is issued, and confirm whether testing against matrimony's account is acceptable before running a live sandbox payment. |

---

*Reference templates: `razorpay-checkout/` (multiplatform, copy this) ·
`razorpay-customui/` (Android-only + iOS stub pattern) · matrimony-kmp
`core/payments/juspay/` (working Android HyperSDK integration). Core contract:
`shared/`. Playbook: `docs/adding-a-new-gateway.md`.*
