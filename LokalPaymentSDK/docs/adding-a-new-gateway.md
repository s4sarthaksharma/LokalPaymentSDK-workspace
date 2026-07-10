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
                          PaymentGateway enum, PaymentResult / LokalPaymentResult / PaymentError
:razorpay-checkout     — the reference gateway: multiplatform (Android + iOS) — the §4 recipe follows it
:razorpay-upi-intent   — Android-only variant (iOS is a stub) — see §3
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
module bootstraps it per platform: a manifest-merged **ContentProvider** on
Android, an **`@EagerInitialization`** hook on iOS.
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
    fun pay(gatewayConfig: JsonObject): Flow<PaymentResult>
}
```

- `pay()` receives **only the opaque `gateway_config` JsonObject** — core has
  already routed by gateway, so you never re-check the gateway or re-parse the
  envelope. You decode the blob into your own typed config (core can't see that
  type — that's why it stays `JsonObject` at the boundary).
- No lifecycle methods: the implementing handler is a singleton `object`, so
  there is nothing to dispose. Per-payment resources live inside `pay()`
  (`callbackFlow` + `awaitClose` detach).

### `LokalPaymentSdk` — the registry + entry point (do not edit)
`shared/.../LokalPaymentSdk.kt` — an `object`. You call `register` (idempotent,
from your handler's `init` block); you never add a branch here. `pay(order)`
routes to `handlers[order.gateway]`.

### `PaymentGateway` — the routing key
`shared/.../model/PaymentGateway.kt`

```kotlin
enum class PaymentGateway(val value: Int) {
    RAZORPAY_CHECKOUT(1), STORE_KIT(2), RAZORPAY_INTENT(3), JUSPAY(4);
    companion object { fun fromValue(value: Int): PaymentGateway? = ... }
}
```

The `value` Ints mirror the **backend's** gateway numbering. `STORE_KIT(2)` and
`JUSPAY(4)` are reserved-but-unimplemented slots. **This enum is the single core
source file a new gateway may need to touch** — see §4 step 1.

### The result model (what your `pay()` emits)
`shared/.../model/PaymentResult.kt`

```kotlin
enum class CancelReason { USER_DISMISSED, UNKNOWN }
data class PaymentError(val code: String?, val message: String)
sealed class PaymentResult {
    data class Success(val paymentId: String, val orderId: String?, val signature: String) : PaymentResult()
    data class Cancelled(val reason: CancelReason) : PaymentResult()
    data class Failure(val error: PaymentError) : PaymentResult()
}
data class LokalPaymentResult(val gateway: PaymentGateway, val result: PaymentResult)  // core wraps yours
```

Your module emits `PaymentResult`. Core wraps it into `LokalPaymentResult`. You
never construct `LokalPaymentResult` yourself.

---

## 3. The shape of a gateway module

The canonical gateway is **multiplatform (Android + iOS)** — `razorpay-checkout`
is the reference, and the §4 recipe is written for it. Most gateways are this
shape. A gateway module:

- puts its SDK entry `object`, config type, result mapper, and (if
  multiplatform) its client `expect`/`actual` in the standard source sets, plus
  its two startup triggers: an `androidMain` InitProvider + manifest entry and
  an `iosMain` `@EagerInitialization` hook;
- takes **no platform handle from the host** — it reads the current
  Activity/UIViewController from `:shared`'s hostcontext utilities at call
  time (Android's ActivityTracker; iOS topmost-UIViewController lookup), the
  way all three shipped gateways do;
- depends on core with `api(project(":shared"))` and on its native SDK with
  `implementation(...)`.

The two single-platform variants below are the exceptions — leave them collapsed
unless you're building one.

<details>
<summary><b>Variant: Android-only gateway</b> (e.g. Razorpay UPI Intent, Juspay) — the <code>razorpay-upi-intent</code> pattern</summary>

Real, shipped example: `:razorpay-upi-intent`. Deltas from the canonical shape:

- **SDK entry object lives in `androidMain`**, not `commonMain`, and its only
  startup trigger is the Android InitProvider — no iOS eager-init hook, so the
  gateway simply never registers on iOS (and never appears in
  `registeredGateways()` there).
- **No client `expect`/`actual`** — just plain Android classes.
- **There is no `iosMain` source at all.** You still declare
  `iosX64/iosArm64/iosSimulatorArm64` targets so a consumer's `commonMain` can
  resolve an iOS variant (without them Gradle fails with "No matching variant
  … platform.type 'native'"), but they just compile an empty klib — the
  rationale lives in the module's build.gradle.kts comment.
- **No `native.cocoapods` plugin / `pod()` block** in `build.gradle.kts`.
- Native dep is the gateway's Android artifact — UPI Intent uses
  `com.razorpay:customui`, a different coordinate from Checkout's
  `com.razorpay:checkout`.
- **Extra host obligations** (UPI Intent specifically): the host supplies a
  `WebView` (Razorpay's JS bridge) and forwards `onActivityResult` — there's no
  SDK-owned proxy Activity, since the host Activity is the one hosting the WebView.

</details>

<details>
<summary><b>Variant: iOS-only gateway</b> (e.g. StoreKit / Apple IAP) — mirror image of the Android-only variant</summary>

No shipped example yet — this maps to the reserved `STORE_KIT(2)` enum slot.
Deltas from the canonical shape (the Android-only variant, flipped):

- **SDK entry object lives in `iosMain`**, not `commonMain`, and its only
  startup trigger is the `@EagerInitialization` hook — no
  Android InitProvider, so the gateway never registers on Android.
- **Android is a stub only.** Declare the Android target so `commonMain` resolves,
  but `androidMain` carries no real API.
- **`native.cocoapods` + `pod()`** stay (for the iOS pod, if any); **no Android
  native dep**. StoreKit itself is a system framework (`platform.StoreKit`), so it
  needs no `pod()` at all.
- ⚠️ **Caveat — StoreKit doesn't drop in cleanly.** An IAP-style gateway has
  products, a restore flow, and a *continuous* transaction stream — it doesn't fit
  the current `Success(paymentId, orderId, signature)` shape or the
  single-terminal-emission model. Adding it needs a core-model discussion first,
  not just a new module. Treat this variant as structural guidance for *any*
  iOS-only gateway, not a claim that StoreKit specifically is a copy-paste job.

</details>

---

## 4. Step-by-step

Assume gateway name `Foo`, backend gateway number `N`, new module `:foo`.

### Step 1 — (core, only if needed) add the `PaymentGateway` entry
In `shared/.../model/PaymentGateway.kt`, add `FOO(N)` with the number the backend
uses. **Skip this** if you're implementing an already-reserved slot
(`STORE_KIT(2)` / `JUSPAY(4)`) — the entry already exists. This is the *only*
edit to `:shared` source a gateway is allowed to make.

### Step 2 — create the module + Gradle wiring
- Create `foo/` with `foo/build.gradle.kts` — **copy** `razorpay-checkout/build.gradle.kts`
  (for an Android-only gateway, copy `razorpay-upi-intent/build.gradle.kts` instead
  — see §3 variants). Then change:
  - `androidLibrary.namespace = "com.getlokalapp.paymentsdk.foo"` (must be unique)
  - cocoapods `name` / `framework.baseName` → `"Foo"` (unique), and the `pod("...")`
    block → the gateway's real CocoaPod. (Android-only gateways have no cocoapods
    block to change.)
  - the Android native dep in `androidMain.dependencies` → the gateway's artifact
    (declare it `implementation`, not `api` — keep the third-party SDK encapsulated).
  - keep `api(project(":shared"))`, the `serialization` plugin, `maven-publish`,
    `group = "com.getlokalapp.paymentsdk"`, and `freeCompilerArgs.add("-Xexpect-actual-classes")`.
- `settings.gradle.kts` (root): add `include(":foo")`.
- `gradle/libs.versions.toml`: add a `[versions]` entry + a `[libraries]` entry
  for the gateway's native SDK (and pin the CocoaPod version in the `pod()` block).

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
internal fun fooSuccess(paymentId: String, orderId: String?, signature: String): PaymentResult =
    PaymentResult.Success(paymentId, orderId, signature)
internal fun fooErrorToResult(code: Int, description: String?): PaymentResult =
    if (code == FooErrorCodes.PAYMENT_CANCELLED) PaymentResult.Cancelled(CancelReason.USER_DISMISSED)
    else PaymentResult.Failure(PaymentError(code = code.toString(), message = description ?: ""))
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

1. **Android — `FooInitProvider` in `androidMain` + a `<provider>` manifest
   entry** (mirror `RazorpayCheckoutInitProvider`; give the authority a unique
   `${applicationId}.…foo.initprovider` suffix). Subclass `:shared`'s
   `SdkInitProvider` — it absorbs the dead ContentProvider overrides, so you
   implement only `onAppStart()`, which touches `FooSdk`. The OS instantiates
   it at process start.
2. **iOS — an `@EagerInitialization` top-level val in `iosMain`** (mirror
   `RazorpayCheckoutEagerInit.kt`, including its warning comment). It runs
   pre-main, so keep the object's `init` a bare in-memory `register()` — no
   logging, no UIKit. ⚠️ The annotation is experimental: if a Kotlin upgrade
   silently no-ops it, registration dies with no compile error — after any
   Kotlin upgrade, verify on iOS that the gateway still appears in
   `LokalPaymentSdk.registeredGateways()`.

A gateway that needs host-supplied setup data before it can pay (Juspay's
init payload) skips the triggers instead: make the object public and give it
an `initialize(...)` method that registers **and** performs setup — the
host's one call is the startup trigger (see `JuspaySdk`).

### Step 6 — platform actuals
- **Android:** a translucent **proxy Activity** (mirroring `RazorpayCheckoutActivity`)
  that implements the gateway's result listener so the *host* never has to. A
  singleton `Bridge` object parks the pending call; the client `startActivity(...)`
  the proxy; the proxy invokes the native SDK and delivers the result through the
  listener exactly once. Declare the proxy Activity in the module's
  `androidMain/AndroidManifest.xml` (`exported=false`, translucent theme) — it
  merges into the host, so consumers register nothing.
- **iOS:** the `actual` client via the cocoapods `pod()` interop.
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
   receives a `PaymentOrder` and returns raw gateway fields in `Success`
   (`paymentId` / `orderId` / `signature`); the host validates server-side. Don't
   add networking to a gateway module.
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
10. **Reserve the enum number to match the backend.** `PaymentGateway.value` is
    the backend's number, not an arbitrary local id — the host maps
    backend-number → enum via `PaymentGateway.fromValue`.

---

## 6. Verification checklist

```bash
# each module compiles independently; :shared still pulls in no gateway SDK
./gradlew :shared:build :razorpay-checkout:build :razorpay-upi-intent:build :foo:build
./gradlew :foo:allTests                 # config decoder + result mapper unit tests
./gradlew :foo:publishToMavenLocal      # then wire into LokalPaymentSDKDemo
```
Then, in `LokalPaymentSDKDemo`, just include the module (no setup code), confirm
`PaymentGateway.FOO in LokalPaymentSdk.registeredGateways()`, and run a real
sandbox payment end-to-end — confirming Success **and** Cancelled **and** Failure
paths, and — for a multiplatform gateway — that iOS extracts `orderId`/`signature`
correctly against a live transaction (this has historically been the
least-verified iOS step).

---

*Templates to copy from: `razorpay-checkout/` (multiplatform) ·
`razorpay-upi-intent/` (Android-only). Core contract: `shared/`.*
