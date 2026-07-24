# Integrating LokalPaymentSDK into an iOS app (SPM)

> **Audience:** an engineer wiring LokalPaymentSDK into a Lokal app's iOS build.
> The SDK is **SPM-only** — CocoaPods has been retired
> (see [`cocoapods-to-spm-migration-plan.md`](cocoapods-to-spm-migration-plan.md)).
> To *add or extend a gateway* inside the SDK, read
> [`adding-a-new-gateway.md`](adding-a-new-gateway.md) instead — this doc is about
> *consuming* the SDK from an app.
>
> The demo (`LokalPaymentSDKDemo/`) is the canonical worked example; every concrete
> value below (`composeApp`, `LokalPaymentSDKDemo`, …) is the demo's — substitute your
> own module and framework names.

---

## How it fits together (30 seconds)

Your app has a **KMP module** (the demo's is `composeApp`) that depends on the SDK's
gateway klibs from Maven and compiles them — plus your own shared code — into **one iOS
XCFramework**. Applying the SDK's `lokal-payment` Gradle plugin makes that module *also*
generate a **local Swift package** at `build/lokal/spmPackage/`, which:

- wraps your XCFramework as a `binaryTarget`,
- adds each active gateway's **vendor SPM package** (Razorpay's `razorpay-pod`, Juspay's
  `hypersdk-ios`) and any **first-party Swift** (native-iap's `NativeIapBridge`),
- ties them into **one umbrella product** your app links.

Two things you own, each done **once**:

1. **Gradle/KMP module config** (§1).
2. **Wiring the generated package into your Xcode project** (§3).

After that, the only recurring step is re-running **one Gradle task** whenever you change
Kotlin (§4). The SDK never edits your `project.pbxproj` — declarative wiring only (D2).

---

## 1. Gradle / KMP module

### `settings.gradle.kts`

Apply the SDK's settings plugin and make the SDK artifacts resolvable. During local
development against a locally-published SDK, include `mavenLocal()`; in CI/production,
replace it with the internal Maven repo the SDK publishes to.

```kotlin
pluginManagement {
    repositories {
        google(); mavenCentral(); gradlePluginPortal()
        mavenLocal() // local dev against a `publishToMavenLocal` SDK
    }
}
plugins {
    id("com.getlokalapp.paymentsdk.lokal-payment-settings") version "0.0.1"
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); mavenLocal() }
}
```

### The KMP module's `build.gradle.kts`

Apply the `lokal-payment` plugin, declare the framework name via `lokalPaymentSdk { }`,
export an `XCFramework` with that name, and depend on the gateways you need:

```kotlin
plugins {
    // …your KMP/Android plugins…
    id("com.getlokalapp.paymentsdk.lokal-payment") version "<sdkVersion>"
}

val hostXcFrameworkName = "MyAppPayments" // demo: "LokalPaymentSDKDemo"

lokalPaymentSdk {
    xcFrameworkName = hostXcFrameworkName   // MUST match the XCFramework(...) name below
}

kotlin {
    val xcf = XCFramework(hostXcFrameworkName)
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = hostXcFrameworkName
            isStatic = true
            xcf.add(this)
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.lokalpaymentsdk.shared)          // core — required
            implementation(libs.lokalpaymentsdk.razorpay.checkout) // opt-in gateways ↓
            implementation(libs.lokalpaymentsdk.razorpay.customui)
            implementation(libs.lokalpaymentsdk.upi.intent)
            implementation(libs.lokalpaymentsdk.native.iap)
            implementation(libs.lokalpaymentsdk.juspay)
            implementation(libs.lokalpaymentsdk.web.checkout)
        }
    }
}
```

Depend only on the gateways you actually use — each one you add contributes its vendor
package (and any per-gateway setup, §5) to the generated Swift package; ones you omit cost
nothing.

---

## 2. Build the XCFramework + generate the package

```bash
./gradlew :composeApp:assembleLokalPaymentSDKDemoReleaseXCFramework
#          └ your module   └ assemble<xcFrameworkName>ReleaseXCFramework
```

This produces `build/XCFrameworks/release/<name>.xcframework` and (re)writes
`build/lokal/spmPackage/` — both `Package.swift` and a generated **`INTEGRATION.md`** with
the exact wiring values for *your* module's gateway selection.

> The package always wraps the **release** XCFramework — a `binaryTarget`'s path is static
> and validated before any build phase runs, so there's no per-configuration swap. One
> release binary links correctly in both Debug and Release, exactly like a vendored SDK.

---

## 3. Wire the generated package into your iOS project (one-time)

Two names, deliberately different — getting them swapped is the most common mistake:

| Purpose | Name (demo) |
| --- | --- |
| **Product** your target links | `<xcFrameworkName>Umbrella` (`LokalPaymentSDKDemoUmbrella`) |
| **Module** you `import` in Swift | `<xcFrameworkName>` (`LokalPaymentSDKDemo`) |

Point your project at `…/<module>/build/lokal/spmPackage`. In a committed spec use a path
relative to the spec file (the demo's iOS project uses `../composeApp/build/lokal/spmPackage`).

### XcodeGen — `project.yml`

```yaml
packages:
  LokalPaymentSDK:
    path: ../composeApp/build/lokal/spmPackage
targets:
  MyApp:
    dependencies:
      - package: LokalPaymentSDK
        product: LokalPaymentSDKDemoUmbrella
```

### Tuist — `Project.swift`

```swift
let project = Project(
    name: "MyApp",
    packages: [.local(path: "../composeApp/build/lokal/spmPackage")],
    targets: [
        .target(
            name: "MyApp",
            // …
            dependencies: [.package(product: "LokalPaymentSDKDemoUmbrella")]
        ),
    ]
)
```

> Exact Tuist syntax tracks your Tuist version — the shape (a `.local` package + a
> `.package(product:)` target dependency) is what matters. Regenerate the project
> (`xcodegen generate` / `tuist generate`) after editing the spec.

### Hand-managed `.xcodeproj` (like the demo)

The demo commits its `.xcodeproj` directly, so it uses the GUI once:
**File ▸ Add Package Dependencies… ▸ Add Local…** → select `composeApp/build/lokal/spmPackage`
→ add the `…Umbrella` product to the app target. If you are **migrating from the old
CocoaPods setup**, also delete the leftover "Compile Kotlin Framework" run-script build
phase (the one calling `embedAndSignAppleFrameworkForXcode`) — the binary target supplies
the framework now, and keeping both double-links. A fresh app never has that phase.

Then, in Swift:

```swift
import LokalPaymentSDKDemo
```

---

## 4. The inner loop

SPM binary targets are **prebuilt** — there is *no* per-Xcode-build Gradle step (that was a
deliberate trade for an idiomatic SPM integration, D1). After any Kotlin change:

```bash
./gradlew :composeApp:assembleLokalPaymentSDKDemoReleaseXCFramework
```

then build in Xcode as usual — SPM re-resolves the local package's refreshed manifest on
every build. If you want this automatic, hang the task on an Xcode **scheme pre-build
action** (the same mechanism Juspay uses in §5); note that re-introduces a per-build Gradle
run, which is the cost D1 chose to avoid.

---

## 5. Per-gateway one-time setup

Most gateways need nothing beyond §1–§3 (Razorpay links entirely via its vendor package;
native-iap via its first-party Swift target). Two exceptions:

### Juspay (HyperSDK)

- Set the `juspayClientId` Gradle property (in `gradle.properties` or `-PjuspayClientId=…`).
  The SDK generates `iosApp/MerchantConfig.json` from it — make sure that file is a member
  of your app target so HyperSDK's asset pipeline can read it.
- Add **one generic** Xcode scheme pre-build action running the generated
  `build/lokal/spmPackage/lokal-prebuild.sh` dispatcher — not a Juspay-specific snippet.
  The plugin itself writes the HyperSDK `Fuse.rb`/`ValidateHyperSDK.rb` invocation into
  `prebuild.d/juspay.sh` (one script per gateway that needs a prebuild step) and the
  dispatcher runs whatever's in `prebuild.d/` — so a second gateway needing its own
  prebuild step in the future would just add another `prebuild.d/<name>.sh`, with no
  change to your scheme. You do **not** need to add Juspay URL/query schemes by hand —
  `ValidateHyperSDK.rb`, run via that dispatcher, writes them into your `Info.plist`.

### UPI intent

The SDK needs the UPI apps' URL schemes under `LSApplicationQueriesSchemes` in your app's
`Info.plist` so `canOpenURL` can detect installed UPI apps. Two ways to get them there:

- **Let the SDK maintain them (recommended).** Point the plugin at your plist:
  ```kotlin
  lokalPaymentSdk {
      xcFrameworkName = "…"
      iosInfoPlist = "../iosApp/iosApp/Info.plist"   // path relative to this module
  }
  ```
  On every Gradle sync it merges the current scheme list into that plist idempotently —
  only adding what's missing, never touching your other entries or reformatting the file.
  Point it at a plist your app target actually uses (a hand-managed `.xcodeproj`, or the
  committed plist an XcodeGen/Tuist spec references) — **not** a fully generated one
  (`GENERATE_INFOPLIST_FILE = YES` with no physical file), which has nothing to patch.
- **Add them by hand.** Leave `iosInfoPlist` unset and the plugin patches nothing — instead
  the generated `INTEGRATION.md` lists the exact `LSApplicationQueriesSchemes` block to paste
  (also visible in the demo's `iosApp/Info.plist`).

---

## The generated `INTEGRATION.md`

Every Gradle sync, the plugin writes `build/lokal/spmPackage/INTEGRATION.md` with the values
above **specialized to your module's actual gateway selection** (only the §5 steps you
enabled appear). Treat *this* guide as the "why/how"; treat that generated file as the
copy-paste "what, for my app right now".
