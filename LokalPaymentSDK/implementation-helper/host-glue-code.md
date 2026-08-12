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
exposes types from kotlinx libraries — `paymentEvents` is a `SharedFlow`, and
`PaymentOrder` takes a `JsonObject` `gatewayConfig` — but `:shared` depends on
those via `implementation`, so they are **not** inherited transitively. The host
must declare them itself (this is the runbook's §5.3 dependency block):

```kotlin
commonMain.dependencies {
    implementation(libs.lokalpaymentsdk.shared)      // REQUIRED — core runtime
    // Needed to reference the SDK's public API types from host code:
    implementation(libs.kotlinx.coroutines.core)     // SharedFlow paymentEvents
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

- a **library product** named `<xcFrameworkName>PaymentsUmbrella` — this is what the app
  target *links*, and
- a **binary target** named `<xcFrameworkName>` (wrapping the `.xcframework`) —
  this is what Swift code *imports*.

So the umbrella-product name and the import name deliberately differ by the
`PaymentsUmbrella` suffix. Don't mix them up.

1. **Generate the package + framework first** (see the runbook's §6 Verify) — the
   folder only exists after a Gradle run. If Xcode can't find it, you built
   nothing yet.

2. **Add the local package to Xcode, once.** By default the plugin does **not**
   edit `project.pbxproj` (no `pod install`-equivalent), so this is a one-time
   manual step; every later regeneration is picked up automatically on Xcode's
   next build.
   - *File ▸ Add Package Dependencies… ▸ Add Local…* → select
     `<module>/build/lokal/spmPackage`.
   - Add the **`<xcFrameworkName>PaymentsUmbrella`** library product to the app target.
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
   import MyHostApp // == xcFrameworkName (NOT MyHostAppPaymentsUmbrella)

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
`PaymentOrder`, starts payment through `LokalPaymentSdk.pay(...)`, and routes
events from `LokalPaymentSdk.paymentEvents`.**

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
        // LokalPaymentEvent.metadata. Correlate SDK attempts with operationId;
        // metadata remains optional host business context.
        metadata = root["metadata"]?.jsonObject,
    )
}
```

> A real host would decode into its own backend DTOs instead of reading the JSON
> tree directly; the shape the SDK needs is just `gateway` + `gatewayConfig`
> (+ optional `metadata`).

### 3.2 Establish one long-lived event collector

Start one collector in an application- or authenticated-session-level owner
before enabling any payment action. Do not create the delivery-critical
collector in the payment screen or immediately beside `pay()`:

```kotlin
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.model.LokalPaymentEvent

class HostPaymentCoordinator(private val scope: CoroutineScope) {
    private val attempts = mutableMapOf<String, HostPaymentState>()

    fun start() {
        scope.launch {
            LokalPaymentSdk.paymentEvents.collect { event ->
                runCatching { route(event) }
                    .onFailure(::reportEventHandlingFailure)
            }
        }
    }

    fun pay(orderResponseJson: String): String {
        val order = parseOrder(orderResponseJson)
        return LokalPaymentSdk.pay(order)
    }

    private fun route(event: LokalPaymentEvent) {
        attempts[event.operationId] = render(event)
    }
}
```

`pay()` returns a UUID string identifying the SDK attempt. It does **not** mean
the gateway accepted, presented, or financially initiated the payment. Those
outcomes arrive on `paymentEvents` with the same `operationId`.

The host must disable/debounce repeated payment taps. The SDK does not block
future payments on a gateway callback or subscriber-count check.

### 3.3 Render `LokalPaymentEvent`

```kotlin
import com.getlokalapp.paymentsdk.model.LokalPaymentEvent
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent

fun render(payment: LokalPaymentEvent): HostPaymentState =
    when (val event = payment.event) {
        PaymentGatewayEvent.GatewayUi.Presented -> HostPaymentState.Presented
        PaymentGatewayEvent.GatewayUi.Dismissed -> HostPaymentState.Dismissed
        is PaymentGatewayEvent.PaymentResult.Success -> {
            verifyWithBackend(payment.operationId, event.gatewayData)
            HostPaymentState.Verifying
        }
        is PaymentGatewayEvent.PaymentResult.Pending -> {
            pollBackendUntilTerminal(payment.operationId, event.gatewayData)
            HostPaymentState.Pending
        }
        is PaymentGatewayEvent.PaymentResult.Cancelled ->
            HostPaymentState.Cancelled(event.reason)
        is PaymentGatewayEvent.PaymentResult.Failure ->
            HostPaymentState.Failed(event.code, event.message)
    }
```

`Success` and `Pending` contain gateway-specific data for backend verification;
the client event is not authoritative financial status.

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
