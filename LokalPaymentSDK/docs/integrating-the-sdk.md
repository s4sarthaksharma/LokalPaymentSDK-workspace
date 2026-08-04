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

Three things you own, each done **once**:

1. **Gradle/KMP module config** (§1).
2. **Bootstrapping the Kotlin binary** so the first Xcode resolve has something to validate (§2).
3. **Wiring the generated package into your Xcode project** (§3) — or pointing the plugin at
   your `.xcodeproj` and letting it do that for you.

After that there is no recurring step: you just build in Xcode (§4).

> **On editing your Xcode files.** The original design (D2) was declarative-only — the SDK
> never touched `project.pbxproj`. That still holds by default, but three **opt-ins** now let
> it edit files you own, because leaving them manual produced silent, late failures rather than
> build errors: `iosInfoPlist` (query schemes), `iosXcodeProject` (the package reference and any
> app-target resources a gateway generates), and `iosXcodeSchemes` (the pre-build action). Every
> such edit is idempotent and announced in the sync log. Set none of them and the SDK writes
> only inside `build/`, exactly as before — which is what XcodeGen/Tuist hosts want, since their
> project files are regenerated from a spec.

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

## 2. Bootstrap the Kotlin binary (one-time, after cloning)

```bash
./gradlew :composeApp:lokalStageKotlinXCFrameworkDebug
#          └ your module
```

This assembles the **debug** XCFramework and stages it at
`build/XCFrameworks/current/<name>.xcframework`, then (re)writes `build/lokal/spmPackage/`
with the `Package.swift` that points at it.

You need this one command before your first Xcode build and never again: SwiftPM validates a
`binaryTarget`'s path while *resolving the package graph*, which happens before any scheme
pre-action or build phase runs — so the very first resolve needs an artifact that already
exists. Skip it and Xcode fails with `local binary target '<name>' … does not contain a
binary artifact`. It builds the debug variant deliberately: it links far faster than release.

> **Which variant Xcode links is automatic from here on.** The `binaryTarget` path is fixed
> (`XCFrameworks/current/`), and the SDK's pre-build action restages that directory from
> `XCFrameworks/debug/` or `XCFrameworks/release/` according to `$CONFIGURATION` on every
> Xcode build — debug Kotlin for Debug builds, optimized for archives. See §4.


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

**Just build in Xcode.** The SDK's scheme pre-build action reassembles and restages the Kotlin
XCFramework on every build, picking the variant that matches your Xcode configuration. You do
not re-run Gradle by hand after editing Kotlin, and there is no stale-binary trap.

That action is registered for you in the schemes covered by
`lokalPaymentSdk { iosXcodeSchemes = … }` — or, with that unset and `iosXcodeProject` set, in
every shared scheme of that project that builds the app target. It runs one generated
dispatcher, `build/lokal/spmPackage/lokal-prebuild.sh`, which also carries any per-gateway
build-time steps (§5), so new gateways never require another scheme edit.

Two consequences worth knowing:

- It reintroduces a per-build Gradle invocation. That's a deliberate reversal of the original
  D1 trade: a prebuilt binary with no build step is only idiomatic until the binary goes stale
  behind your back, which is a much worse failure than a few seconds of Gradle. Gradle no-ops
  when Kotlin hasn't changed.
- Set `LOKAL_SKIP_KOTLIN_ASSEMBLE=1` to suppress it — for CI that assembles the XCFramework
  itself before invoking `xcodebuild`. Make sure such a CI job stages the variant it wants,
  since the skip leaves whatever is in `XCFrameworks/current/` untouched.

---

## 5. Per-gateway one-time setup

Most gateways need nothing beyond §1–§3 (Razorpay links entirely via its vendor package;
native-iap via its first-party Swift target). Two exceptions:

### Juspay (HyperSDK)

- Set the `juspayClientId` Gradle property (in `gradle.properties` or `-PjuspayClientId=…`).
  That single value generates **two** files beside your `.xcodeproj`:
  `iosApp/MerchantConfig.json` (HyperSDK's asset pipeline reads it) and
  `iosApp/LokalJuspayConfig.json` (`:juspay` reads it at runtime to resolve HyperServices'
  `clientId`). You never pass a clientId anywhere in your own code.
- **Both files must be members of your app target.** With `iosXcodeProject` set the SDK adds
  them to your Resources build phase on every sync; otherwise declare them as resources in
  your XcodeGen/Tuist spec. This one bites at *runtime*, not build time — a missing
  `LokalJuspayConfig.json` throws from `IOSJuspayClient.resolveClientId` on the first payment
  call, because `NSBundle.pathForResource` simply returns nil. The sync log's
  `wired resource '…'` lines are the record of what the SDK added.
- The HyperSDK `Fuse.rb` asset download rides the same pre-build dispatcher as everything else
  (§4) — the plugin writes it to `prebuild.d/juspay.sh`, one script per gateway that needs one,
  so a future gateway adds another file and never touches your scheme.
- You do **not** add Juspay URL/query schemes by hand: running `Fuse.rb` patches your
  `Info.plist` itself. It needs the `xcodeproj` Ruby gem on the build machine
  (`gem install xcodeproj`) — without it HyperSDK only warns, and you must add the schemes
  manually. (`ValidateHyperSDK.rb`, which runs afterwards, only *validates*; it does not
  write the plist.)

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
- **Add them by hand.** Leave `iosInfoPlist` unset and the plugin patches nothing — copy the
  `LSApplicationQueriesSchemes` block from the demo's `iosApp/Info.plist`.

---

## What the plugin tells you at sync time

There is no generated companion document. This guide is the whole story; everything the plugin
does to your project it announces in the Gradle sync log:

```
LokalPaymentSDK: wired local package 'ComposeAppUmbrella' into …/project.pbxproj
LokalPaymentSDK: wired resource 'LokalJuspayConfig.json' into …/project.pbxproj
LokalPaymentSDK: registered the prebuild pre-action in …/iosApp.xcscheme
LokalPaymentSDK: merged LSApplicationQueriesSchemes into …/Info.plist
```

Each edit is idempotent, so those lines appear on the sync that actually changed something and
stay quiet afterwards. When something is *not* wired — an empty `iosXcodeSchemes`, or discovery
finding no shared scheme — the plugin warns with the reason and what to do about it.

> Earlier versions generated an `INTEGRATION.md` under `build/`. It was removed: a gitignored
> file can't be reviewed or linked, it duplicated this guide (and drifted from it), and it only
> appeared *after* a successful sync — useless to anyone whose sync was broken. Nothing is
> generated in its place; a leftover copy from an older plugin version goes away with any
> `clean`.
