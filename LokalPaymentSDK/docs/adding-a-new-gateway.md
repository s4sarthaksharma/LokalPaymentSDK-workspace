# Adding a New Payment Gateway — Playbook & Rulebook

**Read this before adding any payment gateway to LokalPaymentSDK.** It is the
authoritative, current-state guide (the code, not the plan). `architecture-reference.md`
and `gateway-modularization-plan.md` are historical planning docs — where they
disagree with this file, **this file wins** (the implemented design evolved past
both: it now has a `PaymentGatewayHandler` interface, a `LokalPaymentSdk`
registry, and a `LokalPaymentResult` envelope that neither plan foresaw).

This doc covers the *SDK-internal* work of adding a gateway. The host-app-side
hard constraint (no Compose in the SDK) lives in `rulebook.md` and applies here
too — read it as well.

---

## 1. The mental model (how a gateway plugs in)

The SDK is a small gateway-agnostic **core** (`:shared`) plus one **opt-in leaf
module per gateway**. A gateway is added, never edited-into. The core knows
nothing about any specific gateway; gateways know the core.

```
:shared                — core: LokalPaymentSdk registry, PaymentGatewayHandler,
                          PaymentGateway enum, PaymentResult / LokalPaymentResult
:gateways:razorpay-checkout     — the reference gateway: multiplatform (Android + iOS) — the §4 recipe follows it
:gateways:razorpay-customui   — Android-only variant (iOS is a stub) — see §3
:your-new-gateway      — you add this, modeled on razorpay-checkout
```

Both leaf modules depend on core with `api(project(":shared"))` (not
`implementation`) — because `Flow<PaymentResult>`, a `:shared` type, appears in
the leaf's own public `pay()` signature. Leaf modules never depend on each other.

### The runtime flow

```
host backend → create-order JSON
   ↓  (host parses; SDK never makes or parses this call)
PaymentOrder(gateway: PaymentGateway, gatewayConfig: JsonObject)
   ↓
LokalPaymentSdk.pay(order)                       ← always the same call
   ↓  looks up handlers[order.gateway]
YourGatewaySdk.pay(gatewayConfig: JsonObject)    ← your handler, self-registered
   ↓  decode config → drive native SDK → normalize callback
Flow<PaymentResult>  (Success / Cancelled / Failure — exactly one, terminal)
   ↓  wrapped by core
Flow<LokalPaymentResult>  (adds the resolved gateway)  → back to host
```

**Registration is automatic — zero host code.** The host never edits SDK
dispatch code, and normally never writes a setup line either. Each gateway's
handler is an app-lifetime singleton `object` whose
`init { LokalPaymentSdk.register(this) }` runs at app startup because the
module bootstraps it per platform: an AndroidX App Startup **`Initializer`**
on Android (contributed to `:shared`'s single init provider via a manifest
`<meta-data>`), an **`@EagerInitialization`** hook on iOS.
The one exception is a gateway that needs host-supplied setup data (Juspay's
init payload) — there the host's single `initialize(...)` call is the trigger.
There is no unregister/dispose: registration is app-lifetime. Routing is by
the `PaymentGateway` enum key. `LokalPaymentSdk.pay()` for an unregistered
gateway returns a `Failure(code = "unsupported_gateway")` — it does not throw.

---

## 2. The core contract you implement against

All in `:shared`, package `com.getlokalapp.paymentsdk`.

### `PaymentGatewayHandler` — the one interface your SDK class implements
`shared/.../PaymentGatewayHandler.kt`

```kotlin
interface PaymentGatewayHandler {
    val gateway: PaymentGateway
    val metadata: GatewayMetadata
    fun readiness(): GatewayReadiness = GatewayReadiness.Ready
    fun pay(gatewayConfig: JsonObject): Flow<PaymentResult>
}
```

- `pay()` receives **only the opaque `gateway_config` JsonObject** — core has
  already routed by gateway, so you never re-check the gateway or re-parse the
  envelope. You decode the blob into your own typed config (core can't see that
  type — that's why it stays `JsonObject` at the boundary).
- `metadata` is your module's own build info (`moduleVersion`, `vendorSdkVersion`,
  optional `extras`) — baked at build time so it can't drift from what actually
  shipped; surfaced to the host via `LokalPaymentSdk.gatewayStatus()`.
- `readiness()` defaults to always-`Ready`. Override it only if your gateway
  needs host-supplied setup before `pay()` can work (Juspay returns `NotReady`
  until the host calls its `initialize(...)`).
- No lifecycle methods beyond that: the implementing handler is a singleton
  `object`, so there is nothing to dispose. Per-payment resources live inside
  `pay()` (`callbackFlow` + `awaitClose` detach).

### `LokalPaymentSdk` — the registry + entry point (do not edit)
`shared/.../LokalPaymentSdk.kt` — an `object`. You call `register` (idempotent,
from your handler's `init` block); you never add a branch here. `pay(order)`
routes to `handlers[order.gateway]`.

### `PaymentGateway` — the routing key
`shared/.../model/PaymentGateway.kt`

```kotlin
enum class PaymentGateway(val code: String) {
    RAZORPAY_CHECKOUT("razorpay_checkout"), NATIVE_IAP("native_iap"),
    RAZORPAY_CUSTOM_UI("razorpay_custom_ui"), JUSPAY("juspay"),
    UPI_INTENT("upi_intent"), WEB_CHECKOUT("web_checkout");
    companion object { fun fromCode(code: String): PaymentGateway? = ... }
}
```

The `code` strings mirror the **backend's** gateway identifier. All six entries
now have a shipped gateway module (`razorpay-checkout`, `razorpay-customui`,
`native-iap`, `juspay`, `upi-intent`, `web-checkout`) — none are reserved-but-
unimplemented anymore. Two are single-platform by design rather than by gap:
`RAZORPAY_CUSTOM_UI` is Android-only (registers `unavailable` on iOS), and
`NATIVE_IAP` is iOS-only for now (registers `unavailable` on Android, pending
Play Billing — see the iOS-only gateway variant in §3). **This enum is the
single core source file a new gateway may need to touch** — see §4 step 1.

### The result model (what your `pay()` emits)
`shared/.../model/PaymentResult.kt`

```kotlin
enum class CancelReason { USER_DISMISSED, UNKNOWN }

// A gateway's pay() Flow emits PaymentGatewayEvent. Its only non-terminal member
// is UiPresented; every PaymentResult *is* a PaymentGatewayEvent (the terminal
// one), so you emit a result directly — trySend(result) — with no wrapper.
sealed interface PaymentGatewayEvent { data object UiPresented : PaymentGatewayEvent }
sealed interface PaymentResult : PaymentGatewayEvent {
    data class Success(val gatewayData: JsonObject) : PaymentResult   // opaque per-gateway blob
    data class Cancelled(val reason: CancelReason) : PaymentResult
    data class Failure(val code: String?, val message: String) : PaymentResult
    data class Pending(val gatewayData: JsonObject) : PaymentResult   // opaque per-gateway blob
}
data class LokalPaymentResult(
    val gateway: PaymentGateway,
    val result: PaymentResult,
    val metadata: JsonObject? = null,  // echoed verbatim from PaymentOrder.metadata
)  // core wraps yours
```

Your module emits `PaymentResult`. Core wraps it into `LokalPaymentResult`. You
never construct `LokalPaymentResult` yourself.

`Success` and `Pending` each carry only an opaque, gateway-specific
`gatewayData: JsonObject` — the fields your gateway returns (ids, a signature, a
txn ref, …) that the frontend never acts on, only forwards to its own backend to
verify (`Success`) or resolve (`Pending`). Each gateway module owns a
`@Serializable` output type it encodes into that blob — the output-side mirror of
your `FooConfig` — so the blob's keys are effectively your backend's verify
contract. Keep out of the blob anything the frontend genuinely branches on; that
belongs in the typed core.

`Failure` and `Cancelled` stay typed on purpose: the frontend *does* act on them
without a backend hop — render an error message/code (`Failure`), or route a
user-cancel away from a failure UI (`Cancelled`).

`Pending` exists for gateways whose outcome isn't known synchronously — UPI
Intent is the only gateway that emits it today, once control hands off to an
external UPI app; the host must resolve the real outcome via its own backend
(keyed on the txn ref inside `gatewayData`). Because `Pending` exists on the
sealed class, every consumer's `when` must handle it even if your gateway never
emits it.

---

## 3. The shape of a gateway module

The canonical gateway is **multiplatform (Android + iOS)** — `razorpay-checkout`
is the reference, and the §4 recipe is written for it. Most gateways are this
shape. A gateway module:

- puts its SDK entry `object`, config type, result mapper, and (if
  multiplatform) its client `expect`/`actual` in the standard source sets, plus
  its two startup triggers: an `androidMain` App Startup `Initializer` +
  manifest `<meta-data>` entry and an `iosMain` `@EagerInitialization` hook;
- takes **no platform handle from the host** — it reads the current
  Activity/UIViewController from `:shared`'s hostcontext utilities at call
  time (Android's ActivityTracker; iOS topmost-UIViewController lookup), the
  way all three shipped gateways do;
- depends on core with `api(project(":shared"))` and on its native SDK with
  `implementation(...)`.

The two single-platform variants below are the exceptions — leave them collapsed
unless you're building one.

<details>
<summary><b>Variant: Android-only gateway</b> (e.g. Razorpay Custom UI, Juspay) — the <code>razorpay-customui</code> pattern</summary>

Real, shipped example: `:gateways:razorpay-customui`. Deltas from the canonical shape:

- **SDK entry object lives in `androidMain`**, not `commonMain`, with **no
  client `expect`/`actual`** — just plain Android classes. Its Android startup
  trigger is the App Startup initializer (`RazorpayCustomUiInitializer`), same
  as the canonical shape.
- **`iosMain` holds a single eager-init hook** (`RazorpayCustomUiEagerInit.kt`).
  Via `@EagerInitialization` it runs before `main()` and calls
  `LokalPaymentSdk.registerUnavailable(...)`, so on iOS the gateway reports
  itself *unavailable* — with a reason a host can read via `gatewayStatus()` —
  rather than silently not existing. It never becomes *available* there, so it
  never appears in `gatewayStatus().available` on iOS.
- **You still declare `iosX64/iosArm64/iosSimulatorArm64` targets** so a
  consumer's `commonMain` can resolve an iOS variant (without them Gradle fails
  with "No matching variant … platform.type 'native'"). The klib isn't empty —
  it carries that unavailable registration. Rationale lives in the module's
  build.gradle.kts comment.
- **No `native.cocoapods` plugin / `pod()` block** in `build.gradle.kts` —
  there's no iOS vendor SDK to link.
- Native dep is the gateway's Android artifact — Razorpay Custom UI uses
  `com.razorpay:customui`, a different coordinate from Checkout's
  `com.razorpay:checkout`.
- **No extra host obligations.** The `WebView` Razorpay's Custom UI flow needs
  is SDK-owned, not host-supplied: `AndroidRazorpayCustomUiClient` reads the
  current Activity from `:shared`'s `ActivityTracker` and launches an internal
  proxy Activity (`RazorpayCustomUiActivity`) that owns the `WebView`, calls
  `submit()`, and handles its own `onActivityResult` — the host forwards
  nothing. (Same proxy-Activity approach as `:gateways:razorpay-checkout`.)

</details>

<details>
<summary><b>Variant: iOS-only-for-now gateway</b> (e.g. NativeIap / StoreKit today, Play Billing on Android later) — mirror image of the Android-only variant</summary>

Shipped: `:gateways:native-iap`, `PaymentGateway.NATIVE_IAP`. Deltas from the canonical
shape (the Android-only variant, flipped):

- **SDK entry object lives in `iosMain`**, not `commonMain`
  (`NativeIapSdk.kt`), and its only startup trigger is the
  `@EagerInitialization` hook (`NativeIapEagerInit.kt`) — no Android App
  Startup initializer for now, so the gateway doesn't register on Android yet.
  Unlike the permanent Android-only variant, this side is meant to get a real
  implementation later (Play Billing), not stay a stub forever.
- **Android is a stub in the meantime.** iOS/Android targets are declared so
  `commonMain` resolves on both, and `NativeIapUnavailableInitializer`
  registers the gateway as unavailable via `LokalPaymentSdk.registerUnavailable(...)`
  (mirrors `RazorpayCustomUiEagerInit.kt`, just on the opposite platform) — no
  real API until Play Billing lands.
- ⚠️ **StoreKit doesn't cinterop like a normal vendor pod, at all.** StoreKit
  2's API (`Product`, `Transaction`, `VerificationResult`) is pure Swift async
  / `AsyncSequence` — it isn't `@objc`-visible, so Kotlin/Native's `cinterop`
  (a clang-based tool that only reads Objective-C headers) can never call it
  directly, the way `razorpay-checkout` cinterops straight against Razorpay's
  Objective-C SDK. **An alternative was tried and dropped:** a plain Kotlin
  interface (`NativeIapStoreKitProvider`) that the *host* implements in Swift
  and registers at launch — technically sound (Kotlin/Native always generates
  an Objective-C-visible header for its own framework, so that direction needs
  no cinterop at all, and it's the same shape matrimony-kmp's production
  `StoreKitProvider` uses) — but it means the host owns and maintains real
  StoreKit business logic, which breaks rule 8 ("the host never implements a
  gateway's callback interface") for this one gateway. **Settled on:** this
  module vendors its own small Objective-C-visible bridge
  (`ios/NativeIapBridge/NativeIapBridge.swift`) — real Swift StoreKit 2 code
  wrapped behind a plain-`NSObject`, completion-handler-based surface. There is
  **no CocoaPods anywhere in this build** (see
  `docs/cocoapods-to-spm-migration-plan.md`): the module's own
  `generateNativeIapBridgeInterface` Gradle task runs `swiftc
  -emit-objc-header-path` directly on that Swift file to produce just the
  generated Objective-C header + a modulemap, and the `NativeIapBridge`
  cinterop compiles `iosMain`'s bindings against that header — no binary is
  built on the SDK side, only the interface. The actual Swift file is compiled
  and linked later, on the **consumer** side, as an SPM source target:
  `:native-iap:host-contributor` ships `NativeIapBridge.swift` (resolved from
  this module's `iossrc`-classifier Maven artifact, via
  `registerIosPodSourcePublication`) directly into the generated
  `Package.swift`'s umbrella product, linked against `StoreKit`. The iOS actual
  (`IOSNativeIapClient.kt`) cinterops into the generated header, and
  registration goes through the normal `@EagerInitialization` hook
  (`NativeIapEagerInit.kt`) — **zero host Swift code and zero Podfile lines**,
  the same guarantee every other gateway makes; the host-contributor mechanism
  (`LokalGatewayHostContributor` → `HostContribution.sourceTarget`) is exactly
  how `native-iap` gets its first-party Swift into the host's build, the same
  extension point that injects Razorpay's and Juspay's vendor SPM packages.
  One naming gotcha hit building this: Kotlin/Native imports a Swift `@objc
  enum`'s cases as **top-level constants** in the cinterop package
  (`NativeIapOutcomeSuccess`, not `NativeIapOutcome.Success` /
  `NativeIapOutcome.NativeIapOutcomeSuccess`) — import them individually
  rather than qualifying through the enum type.
- **Result-model resolution:** StoreKit's purchase result has more cases
  (success / unverified / cancelled / pending / failure) than `PaymentResult`,
  plus a continuous transaction-updates stream for deferred/restored
  transactions that's independent of any single purchase call. Collapsed at
  the result-mapper layer (`NativeIapResult.kt`): `unverified` → `Failure`;
  `pending` → don't emit yet, keep the `pay()` `callbackFlow` open
  (`NativeIapSdk.kt`) and also listen to the transaction-updates stream for
  the matching terminal transaction before emitting and closing. `pay()`
  still emits exactly one terminal `PaymentResult` (rule 7) — the waiting
  happens inside it, not via a second API.

</details>

---

## 4. Step-by-step

Assume gateway name `Foo`, backend gateway code `"foo"`, new module `:foo`.

### Step 1 — (core, only if needed) add the `PaymentGateway` entry
In `shared/.../model/PaymentGateway.kt`, add `FOO("foo")` with the code the
backend uses. **Skip this** if you're implementing an already-reserved slot
(`NATIVE_IAP("native_iap")` / `JUSPAY("juspay")`) — the entry already exists.
This is the *only* edit to `:shared` source a gateway is allowed to make.

### Step 2 — create the module + Gradle wiring
- Create `foo/` with `foo/build.gradle.kts` — **copy** `razorpay-checkout/build.gradle.kts`
  (for an Android-only gateway, copy `razorpay-customui/build.gradle.kts` instead
  — see §3 variants). There is **no CocoaPods anywhere in this build**
  (see `docs/cocoapods-to-spm-migration-plan.md`) — iOS vendor SDKs are linked
  via direct Kotlin/Native cinterop against a fetched `.xcframework`, not a
  `pod()` block. Then change:
  - `androidLibrary.namespace = "com.getlokalapp.paymentsdk.foo"` (must be unique)
  - the vendor-fetch task (`fetchRazorpayXcFramework` in the reference module) →
    rename it and point it at wherever the gateway's real `.xcframework` is
    published (a GitHub release tarball, a CDN URL, etc. — see
    `razorpay-checkout/build.gradle.kts` and `juspay/build.gradle.kts` for two
    different real fetch strategies), and update the `.def` file(s) under
    `src/nativeInterop/cinterop/` plus the `cinterops.create("...")` blocks in
    the `iosArm64`/`iosX64`/`iosSimulatorArm64` targets to point at the new
    module map. (Android-only gateways have no iOS cinterop to change at all.)
  - the Android native dep in `androidMain.dependencies` → the gateway's artifact
    (declare it `implementation`, not `api` — keep the third-party SDK encapsulated).
  - keep `api(project(":shared"))`, the `serialization` plugin, `maven-publish`,
    `group = "com.getlokalapp.paymentsdk"`, and `freeCompilerArgs.add("-Xexpect-actual-classes")`.
- `settings.gradle.kts` (root): add `include(":foo")`.
- `gradle/libs.versions.toml`: add a `[versions]` entry + a `[libraries]` entry
  for the gateway's native SDK (Android artifact coordinate, and the iOS vendor
  SDK's version string used by the fetch task above).
- If the gateway needs a **vendor SPM package or first-party Swift** linked into
  the host's iOS build (most do — see `razorpay-checkout/host-contributor/` and
  `native-iap/host-contributor/`), add a `foo/host-contributor` module
  implementing `LokalGatewayHostContributor` (§4 step 8's iOS twin — not
  covered by a numbered step here since not every gateway needs one; follow
  the reference modules directly).

### Step 3 — the config type + decoder (`commonMain`)
`FooConfig.kt`:
```kotlin
@Serializable
internal data class FooConfig(
    @SerialName("razorpay_key") val key: String,   // whatever gateway_config actually carries
    @SerialName("data") val data: JsonObject,        // opaque; handed straight to the native SDK
)
private val lenientJson = Json { ignoreUnknownKeys = true }   // tolerate sibling fields e.g. order_row_id
internal fun JsonObject.toFooConfig(): FooConfig =
    lenientJson.decodeFromJsonElement(FooConfig.serializer(), this)
```

### Step 4 — result mapping (`commonMain`)
`FooResult.kt` (error codes + `PaymentResult` mappers):
```kotlin
internal object FooErrorCodes { const val PAYMENT_CANCELLED = 0 /* the gateway's own cancel code */ }

// Output-side mirror of FooConfig: the success fields your backend verifies,
// encoded into Success.gatewayData under the keys your backend expects.
@Serializable
internal data class FooResult(
    @SerialName("payment_id") val paymentId: String,
    @SerialName("signature") val signature: String?,
)
internal fun fooSuccess(paymentId: String, signature: String?): PaymentResult =
    PaymentResult.Success(FooResult(paymentId, signature).toJsonObject())  // toJsonObject: :shared helper
internal fun fooErrorToResult(code: Int, description: String?): PaymentResult =
    if (code == FooErrorCodes.PAYMENT_CANCELLED) PaymentResult.Cancelled(CancelReason.USER_DISMISSED)
    else PaymentResult.Failure(code = code.toString(), message = description ?: "")
```
**Classify cancel-vs-failure here**, one layer up from the platform client, using
the gateway's own cancel code (Checkout uses `0`; UPI Intent uses `5` — they
differ per gateway, so never hardcode a shared value). Never conflate a user
cancel with a real failure — the host routes them to different UI.

### Step 5 — the SDK entry object (`PaymentGatewayHandler`) + startup triggers
Put the object in `commonMain` (single-platform gateways put it in
`androidMain`/`iosMain` — see §3 variants). It's `internal`: hosts never
reference it — the platform triggers below run its registering `init` block.
```kotlin
internal object FooSdk : PaymentGatewayHandler {
    override val gateway = PaymentGateway.FOO
    init { LokalPaymentSdk.register(this) }              // ← the whole registration mechanism
    override fun pay(gatewayConfig: JsonObject): Flow<PaymentResult> = callbackFlow {
        val config = gatewayConfig.toFooConfig()
        val client = createFooClient()                    // multiplatform: expect/actual factory; single-platform: just new it
        client.setPaymentResultListener(object : FooResultListener {
            override fun onPaymentSuccess(id, orderId, sig) { trySend(fooSuccess(id, orderId, sig)); close() }
            override fun onPaymentError(code, desc)         { trySend(fooErrorToResult(code, desc)); close() }
        })
        client.open(config)
        awaitClose { client.setPaymentResultListener(null) }   // detach on cancellation/close
    }
}
```
Emit **exactly one** terminal result, then `close()`. `callbackFlow` +
`awaitClose` is the standard shape.

Kotlin objects initialize lazily (first reference), so each platform needs a
startup trigger that references the object with zero host code — copy both
pieces from `razorpay-checkout`:

1. **Android — `FooInitializer` in `androidMain` + a `<meta-data>` manifest
   entry** (mirror `RazorpayCheckoutInitializer`). Extend `:shared`'s
   `GatewayInitializer` (an AndroidX App Startup `Initializer`) and implement
   only `create()`, touching `FooSdk` to run its `init`; the base already
   depends on `PaymentSdkInitializer` so the ActivityTracker install runs
   first. No `androidx.startup` dependency in your module — it comes
   transitively from `:shared` (`api`). Register it with a `<meta-data>`
   (keyed by the initializer's class name — no per-module authority to pick)
   merged into App Startup's single `InitializationProvider`. It runs
   synchronously at process start, before `Application.onCreate()`.
2. **iOS — an `@EagerInitialization` top-level val in `iosMain`** (mirror
   `RazorpayCheckoutEagerInit.kt`, including its warning comment). It runs
   pre-main, so keep the object's `init` a bare in-memory `register()` — no
   logging, no UIKit. ⚠️ The annotation is experimental: if a Kotlin upgrade
   silently no-ops it, registration dies with no compile error — after any
   Kotlin upgrade, verify on iOS that the gateway still appears in
   `LokalPaymentSdk.gatewayStatus().available`.

A gateway that needs host-supplied setup data before it can pay (Juspay's
init payload) skips the triggers instead: make the object public and give it
an `initialize(...)` method that registers **and** performs setup — the
host's one call is the startup trigger (see `JuspaySdk`).

### Step 5b — logging

Log through `Log` (`com.getlokalapp.util.Log`) rather than `println`/
`Log.d`(android.util)/`NSLog` — it's a no-op until a host installs a real
`LokalLogger` via `LokalPaymentSdk.setLogger()`, so unlogged hosts pay
nothing for it. Add a `private const val TAG = "Foo"` to your SDK object and:
- `Log.d { "[$TAG] ..." }` right before the call that kicks off vendor UI
  (`client.open(...)`, etc.), and in each listener branch for success/cancel
- `Log.w`/`Log.e(err, tag) { ... }` for error branches
- Never log the full `gatewayConfig`/init-payload `JsonObject` or raw card/
  customer data — only ids, codes, and structural facts. (The terminal
  `Success`/`Pending` `gatewayData` blob *is* logged in full by `describeForLog()`,
  signature included — a deliberate choice on the output path; it does not license
  logging the *input* config or card data.) `LokalPaymentSdk.pay()` already logs
  every gateway's `UiPresented`/terminal events uniformly; your gateway's own
  logging should add detail the orchestrator can't see (vendor SDK internals),
  not repeat it.

**Never call `Log` from inside your SDK object's own `init` block.** See
Rulebook §5 rule 11 — that block runs from every gateway's eager startup
trigger, including pre-`main` on iOS.

### Step 6 — platform actuals
- **Android:** a translucent **proxy Activity** (mirroring `RazorpayCheckoutActivity`)
  that implements the gateway's result listener so the *host* never has to. A
  singleton `Bridge` object parks the pending call; the client `startActivity(...)`
  the proxy; the proxy invokes the native SDK and delivers the result through the
  listener exactly once. Declare the proxy Activity in the module's
  `androidMain/AndroidManifest.xml` (`exported=false`, translucent theme) — it
  merges into the host, so consumers register nothing.
- **iOS:** the `actual` client via direct cinterop against the fetched
  `.xcframework` (no CocoaPods, no `pod()` block — see Step 2).
- **Single-platform gateways** stub the other side instead — see the §3 variants
  for why the stubbed-side targets must still be declared.

### Step 7 — JSON conversion helpers ⚠️ name collision gotcha
The native SDKs want a different JSON type than kotlinx (`org.json.JSONObject` on
Android, a plain `Map`/`NSDictionary` on iOS), so you'll add `toOrgJson()` /
`toPlainMap()` helpers. **If your module uses the same package as an existing
gateway** (`com.getlokalapp.paymentsdk.razorpay`), a same-named top-level file
(`JsonObjectConversions.kt`) compiles to the same JVM class name
(`JsonObjectConversionsKt`) and **collides** for a host that depends on both
modules. **Safest fix: give your new module its own package**
(`com.getlokalapp.paymentsdk.foo`). If you must share the package, give the file
a unique name.

### Step 8 — host wiring (in `LokalPaymentSDKDemo`, or the real host)
The host does **zero** SDK-code changes and writes **zero** setup lines. It:
1. adds the module dependency + publishes it (`./gradlew :foo:publishToMavenLocal`),
2. keeps calling `LokalPaymentSdk.pay(order)` — registration happened at app
   startup via the module's own triggers, and routing is automatic. (Only a
   setup-data gateway like Juspay needs one host line:
   `FooSdk.initialize(...)` at app startup.)

### Step 8b — (only if your gateway needs settings-phase setup) a settings contributor
Most gateways need nothing here. But if your gateway's native SDK lives in a
**private Maven repo** (like Juspay's `maven.juspay.in`) or otherwise needs
`settings.gradle.kts`-level wiring, do **not** ask the host to edit its
`settings.gradle.kts`. Instead ship a `LokalGatewaySettingsContributor` — the
settings-phase twin of the iOS `LokalGatewayHostContributor`:
- Create a plain jar module (e.g. `:foo:settings-contributor`) — **not** a
  `java-gradle-plugin` — depending only on `:gradle-plugins:settings-spi` (never on the
  gateway's own vendor artifact/repo; that would reintroduce the chicken-and-egg the
  contributor exists to solve).
- Implement `LokalGatewaySettingsContributor.contribute(settings)` to add your repo
  to `pluginManagement` + `dependencyResolutionManagement`, register it in
  `META-INF/services/com.getlokalapp.paymentsdk.host.LokalGatewaySettingsContributor`,
  and add `implementation(project(":foo:settings-contributor"))` to
  `:gradle-plugins:settings-plugin` so the umbrella discovers it.
- Contribute **unconditionally** — settings evaluation can't see the module
  dependency graph, so there's no self-gate (adding an unused repo is a harmless
  no-op). The host still applies only `com.getlokalapp.paymentsdk.lokal-payment-settings`.

---

## 5. Rulebook — hard rules & non-negotiables

1. **Never edit `LokalPaymentSdk` dispatch or add a gateway `when`-branch anywhere
   in `:shared`.** Gateways are added by registration, not by editing core. The
   only permitted `:shared` source edit is a new `PaymentGateway` enum entry (§4
   step 1).
2. **No Compose / Compose Multiplatform in any SDK module** — see `rulebook.md`.
   Registration is a plain `init{}` run by the module's own startup triggers;
   there is no cleanup — handlers are app-lifetime `object`s. Compose glue
   belongs to the *host*.
3. **The gateway module owns its native SDK dependency as `implementation`**, never
   `api` — a host that doesn't use your gateway must not transitively pull its
   third-party SDK. Keep `api` for `project(":shared")` only.
4. **The SDK never makes or parses the create-order or validate calls.** It
   receives a `PaymentOrder` and returns an opaque per-gateway blob in
   `Success.gatewayData` (your `@Serializable` output type, encoded to a
   `JsonObject`); the host forwards it to its own validate endpoint server-side.
   Don't add networking to a gateway module.
5. **`gateway_config.data` is opaque — pass it straight to the native SDK, never
   inspect or reshape it.** Decode only the outer fields you need (the key,
   `data`). Use `ignoreUnknownKeys = true` so backend-added sibling fields don't
   break decoding.
6. **Classify cancellation vs. failure at the result-mapper layer, per the
   gateway's own cancel code.** One `Cancelled` for user-dismissal, `Failure` for
   everything else. Never conflate them; never hardcode another gateway's code.
7. **Emit exactly one terminal `PaymentResult` then complete the flow.** Guard
   against double-open / double-emit (`callbackFlow` + `close()` + `awaitClose`
   detach). A leaked in-flight state is a real production bug class (see
   `architecture-reference.md` §1.4).
8. **The host never implements a gateway's callback interface.** Absorb that with
   an SDK-owned proxy Activity (Android) / delegate (iOS), so the host's only
   contact surface is `LokalPaymentSdk.pay()` (plus `initialize(...)` for a
   setup-data gateway).
9. **Unique everything across modules:** Android `namespace`, cocoapods `name` /
   `framework.baseName`, and — critically — top-level file names within a shared
   package (JVM class-name collision, §4 step 7). Prefer a per-module package.
10. **Reserve the enum code to match the backend.** `PaymentGateway.code` is
    the backend's identifier, not an arbitrary local id — the host maps
    backend-code → enum via `PaymentGateway.fromCode`.
11. **No `Log` calls inside a gateway object's `init` block** — same rule as
    "no logging, no UIKit" for the iOS `@EagerInitialization` hook (§4 step 5,
    Step 5 above): that block runs synchronously from every gateway's eager
    startup trigger, pre-`main` on iOS, so it must stay a bare `register()`
    call. Logging belongs inside listener lambdas and methods that run later,
    at actual runtime events (see Step 5b).

---

## 6. Verification checklist

```bash
# each module compiles independently; :shared still pulls in no gateway SDK
./gradlew :shared:build :gateways:razorpay-checkout:build :gateways:razorpay-customui:build :foo:build
./gradlew :foo:allTests                 # config decoder + result mapper unit tests
./gradlew :foo:publishToMavenLocal      # then wire into LokalPaymentSDKDemo
```
Then, in `LokalPaymentSDKDemo`, just include the module (no setup code), confirm
`PaymentGateway.FOO` appears in `LokalPaymentSdk.gatewayStatus().available`, and run a real
sandbox payment end-to-end — confirming Success **and** Cancelled **and** Failure
paths, and — for a multiplatform gateway — that iOS populates `Success.gatewayData`
correctly against a live transaction (this has historically been the
least-verified iOS step).

---

*Templates to copy from: `razorpay-checkout/` (multiplatform) ·
`razorpay-customui/` (Android-only). Core contract: `shared/`.*
