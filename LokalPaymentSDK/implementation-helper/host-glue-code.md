# LokalPaymentSDK — Host Glue Code (usage companion)

> **What this is.** The host-side *usage* companion to
> [`host-integration-runbook.md`](./host-integration-runbook.md). The runbook
> gets the SDK onto your classpath (plugins, catalog, dependencies, iOS
> umbrella); **this** doc shows how to actually call it — the platform entry
> points and the host-owned glue that converts a backend response into a
> `PaymentOrder`, calls `LokalPaymentSdk.pay(...)`, and renders the result.
>
> **Reference implementation.** Every snippet is drawn from the working demo
> host `LokalPaymentSDKDemo` (sibling repo) — mirror `App.kt` / `Conversions.kt`
> when in doubt.

---

## 1. Prerequisites

Assumes the build wiring in the runbook is already done — SDK plugins applied,
`com.getlokalapp.paymentsdk:shared` (plus any gateways) resolvable.

One dependency detail matters for the code below. The SDK's **public** API
exposes types from kotlinx libraries — `pay(...)` returns a `Flow`, and
`PaymentOrder` takes a `JsonObject` `gatewayConfig` — but `:shared` depends on
those via `implementation`, so they are **not** inherited transitively. The host
must declare them itself (this is the runbook's §5.3 dependency block):

```kotlin
commonMain.dependencies {
    implementation(libs.lokalpaymentsdk.shared)      // REQUIRED — core runtime
    // Needed to reference the SDK's public API types from host code:
    implementation(libs.kotlinx.coroutines.core)     // Flow returned by pay()
    implementation(libs.kotlinx.serialization.json)  // JsonObject gatewayConfig
}
```

---

## 2. Platform entry points

### 2.1 Android

The host Activity just hosts the shared UI (or calls the SDK directly from its
own screens). Demo pattern:

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App() } // App() is the host's own composable that calls the SDK
    }
}
```

Nothing SDK-specific is required in the Android manifest for the core; individual
gateways may have their own manifest needs — consult each gateway's docs.

### 2.2 iOS — Kotlin entry point (iOS hosts only)

Expose a `UIViewController` (or a plain API) the Swift app can call. Demo pattern
(`iosMain`):

```kotlin
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
```

### 2.3 iOS — Swift app + Xcode wiring (iOS hosts only)

The `lokal-payment` plugin generates a **local Swift package** at
`<module>/build/lokal/spmPackage`. Its Package.swift declares:

- a **library product** named `<xcFrameworkName>Umbrella` — this is what the app
  target *links*, and
- a **binary target** named `<xcFrameworkName>` (wrapping the `.xcframework`) —
  this is what Swift code *imports*.

So the umbrella-product name and the import name deliberately differ by the
`Umbrella` suffix. Don't mix them up.

1. **Generate the package + framework first** (see the runbook's §6 Verify) — the
   folder only exists after a Gradle run. If Xcode can't find it, you built
   nothing yet.

2. **Add the local package to Xcode, once.** By default the plugin does **not**
   edit `project.pbxproj` (no `pod install`-equivalent), so this is a one-time
   manual step; every later regeneration is picked up automatically on Xcode's
   next build.
   - *File ▸ Add Package Dependencies… ▸ Add Local…* → select
     `<module>/build/lokal/spmPackage`.
   - Add the **`<xcFrameworkName>Umbrella`** library product to the app target.
   - (In the demo `iosApp.xcodeproj` this shows up as an
     `XCLocalSwiftPackageReference` with
     `relativePath = ../composeApp/build/lokal/spmPackage`.)
   - **Automate it (hand-managed `.xcodeproj` only).** Instead of the manual
     "Add Local…", set `lokalPaymentSdk { iosXcodeProject = "<path>.xcodeproj" }`
     in the module's `build.gradle.kts` and the plugin performs this wiring for you
     on every Gradle sync — idempotently (a project already wired is left untouched),
     the exact sibling of the `iosInfoPlist` opt-in. **Do not** set it on an
     XcodeGen/Tuist host: those regenerate `project.pbxproj` from a spec and would
     clobber the edit — declare the package in the spec instead (see below).

3. **Import the binary framework** (name == `xcFrameworkName`, *not* the umbrella)
   and bridge the controller:

   ```swift
   import SwiftUI
   import MyHostApp // == xcFrameworkName (NOT MyHostAppUmbrella)

   struct ComposeView: UIViewControllerRepresentable {
       func makeUIViewController(context: Context) -> UIViewController {
           MainViewControllerKt.MainViewController()
       }
       func updateUIViewController(_ vc: UIViewController, context: Context) {}
   }
   ```

---

## 3. Host-owned glue code

The SDK is JSON-agnostic: **the host converts its backend response into a typed
`PaymentOrder`, calls `LokalPaymentSdk.pay(...)`, and renders the
`LokalPaymentResult`.** Mirror the demo's `Conversions.kt` and `App.kt`.

### 3.1 Backend JSON → `PaymentOrder`

```kotlin
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentOrder
import kotlinx.serialization.json.*

private val orderJson = Json { ignoreUnknownKeys = true }

fun parseOrder(orderResponseJson: String): PaymentOrder {
    val root = orderJson.parseToJsonElement(orderResponseJson).jsonObject
    val gatewayCode = root.getValue("gateway").jsonPrimitive.content
    val gateway = PaymentGateway.fromCode(gatewayCode)
        ?: error("Unknown gateway code from backend: $gatewayCode")
    return PaymentOrder(
        gateway = gateway,
        gatewayConfig = root.getValue("gateway_config").jsonObject,
        // Optional host-owned passthrough — the SDK carries it back untouched on
        // LokalPaymentResult.metadata so you can correlate the result to the call.
        metadata = root["metadata"]?.jsonObject,
    )
}
```

> A real host would decode into its own backend DTOs instead of reading the JSON
> tree directly; the shape the SDK needs is just `gateway` + `gatewayConfig`
> (+ optional `metadata`).

### 3.2 Call the SDK and collect the result

`LokalPaymentSdk.pay(order)` returns a `Flow<LokalPaymentResult>`:

```kotlin
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import kotlinx.coroutines.flow.catch

scope.launch {
    val order = parseOrder(orderResponseJson)
    LokalPaymentSdk.pay(order)
        .catch { status = "Error: ${it.message}" }
        .collect { result -> status = render(result) }
}
```

### 3.3 Render `LokalPaymentResult`

```kotlin
import com.getlokalapp.paymentsdk.model.LokalPaymentResult
import com.getlokalapp.paymentsdk.model.PaymentResult

fun render(payment: LokalPaymentResult): String = when (val r = payment.result) {
    is PaymentResult.Success   -> "Success: ${r.paymentId} / ${r.orderId} / ${r.signature}"
    is PaymentResult.Cancelled -> "Cancelled: ${r.reason}"
    is PaymentResult.Failure   -> "Failure: ${r.error.code} ${r.error.message}"
    is PaymentResult.Pending   -> "Pending: ${r.txnRef} (${r.clientHint}) — verify with backend"
} + (payment.metadata?.let { "\nmetadata = $it" } ?: "")
```

### 3.4 Discovery APIs (optional)

```kotlin
LokalPaymentSdk.VERSION                 // version string
LokalPaymentSdk.gatewayStatus()         // which gateways registered; .available drives your UI
LokalPaymentSdk.installedUpiApps()      // platform UPI-app detection (List<UpiApp>)
```

Use `gatewayStatus().available` to decide which payment options to surface —
only show a gateway if it registered itself.

### 3.5 Gateways that need explicit initialization

Some gateways require a one-time init before `pay(...)`. **Juspay** is the known
case — initialize it once (e.g. at app/screen start) before it appears as
available:

```kotlin
import com.getlokalapp.paymentsdk.juspay.JuspaySdk

JuspaySdk.initialize(initPayloadJson, clientId = "<yourClientId>")
```

Check each gateway's own docs for init/manifest/Info.plist requirements — those
are gateway-specific and out of scope here.
