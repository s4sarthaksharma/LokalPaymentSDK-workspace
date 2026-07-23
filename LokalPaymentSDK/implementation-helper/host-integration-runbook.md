# LokalPaymentSDK — Host Integration Runbook (for a Claude agent)

> **What this is.** A prescriptive, step-by-step playbook you hand to a Claude
> agent so it wires **LokalPaymentSDK** into a **new host project**. It covers
> everything *apart from* authoring gateways — i.e. the build plumbing, the core
> dependency, the platform entry points, and the host-owned glue code needed to
> call `LokalPaymentSdk.pay(...)`.
>
> **Reference implementation.** Every snippet below is drawn from the working
> demo host `LokalPaymentSDKDemo` (sibling repo). When in doubt, open the demo
> and mirror it — it is the source of truth.

---

## 0. Agent operating rules

Read these before touching anything.

1. **Detect, then adapt — do not blindly paste.** The snippets assume a
   Kotlin-DSL, version-catalog, Kotlin-Multiplatform (KMP) host with iOS
   targets (the demo's shape). If the target project differs, adjust or **stop
   and ask** (see §1).
2. **Insert into existing blocks; never blind-append.** `settings.gradle.kts`,
   `build.gradle.kts`, and `libs.versions.toml` already contain `plugins {}`,
   `pluginManagement {}`, `dependencyResolutionManagement {}`, `kotlin {}`,
   `[versions]`, `[libraries]`, `[plugins]`. Add lines *inside* the right block.
3. **Be idempotent.** Before adding any line, check whether it already exists.
   If present, skip it. Running this runbook twice must not duplicate anything.
4. **Do not add `mavenLocal()`.** Artifact resolution is handled by the host's
   existing repository setup. Your job is the plugin/dependency wiring, not the
   publication/repo story. (If a build fails purely because the
   `com.getlokalapp.paymentsdk:*` artifacts can't be resolved, stop and report
   it — that's a repo-access problem for the human to fix, not something to
   patch with `mavenLocal`.)
5. **Verify at the end** (§6). Don't declare done until the build passes.
6. **The SDK modules must stay UI-framework-agnostic.** This runbook only edits
   the *host* app, which may use Compose — that's fine. Never add Compose to the
   SDK modules themselves.

**Coordinates (invariant — copy verbatim):**

| Thing | Value |
|---|---|
| Group | `com.getlokalapp.paymentsdk` |
| Version | `0.0.1` |
| Settings plugin id | `com.getlokalapp.paymentsdk.lokal-payment-settings` |
| Per-module (iOS umbrella) plugin id | `com.getlokalapp.paymentsdk.lokal-payment` |
| Core runtime dependency | `com.getlokalapp.paymentsdk:shared` |

Optional gateway dependencies (add only the ones the host needs):

| Gateway | Artifact |
|---|---|
| Razorpay Checkout | `com.getlokalapp.paymentsdk:razorpay-checkout` |
| Razorpay Custom UI (UPI intent via Razorpay) | `com.getlokalapp.paymentsdk:razorpay-customui` |
| Generic UPI Intent | `com.getlokalapp.paymentsdk:upi-intent` |
| Juspay | `com.getlokalapp.paymentsdk:juspay` |
| Native IAP (StoreKit / Play Billing) | `com.getlokalapp.paymentsdk:native-iap` |
| Web Checkout | `com.getlokalapp.paymentsdk:web-checkout` |

---

## 1. Detect the target project (gather inputs)

Inspect the repo and determine each of the following. If any is ambiguous,
**stop and ask the user** rather than guessing.

1. **Build DSL** — Kotlin (`*.gradle.kts`) or Groovy (`*.gradle`)? (Snippets
   below are Kotlin DSL. For Groovy, translate syntax.)
2. **Version catalog** — is there a `gradle/libs.versions.toml`? If **yes**, use
   the catalog path (§3 + catalog aliases). If **no**, inline coordinates
   directly in the build file (see the "no catalog" note in §4).
3. **KMP module** — which module holds the shared/common code and iOS targets?
   In the demo it's `composeApp`. Find the module applying
   `kotlin.multiplatform` / `org.jetbrains.kotlin.multiplatform`. Call it
   `<module>` below.
4. **iOS targets present?** Does `<module>` declare `iosArm64()` /
   `iosSimulatorArm64()` and an Xcode app under `iosApp/` (or similar)? If the
   host is **Android-only**, skip the iOS-only steps (the
   `lokalPaymentSdk {}` / XCFramework config in §5.2).
5. **Which gateways** does the host want enabled? Default to only what the user
   names; otherwise ask. (Core `:shared` is always required.)
6. **Juspay?** If Juspay is in scope, the Juspay Maven repo must be reachable
   (see §2).

---

## 2. Step 0 — Repository prerequisites

You are **not** adding `mavenLocal()`. But confirm the host can resolve the
artifacts:

- The repository that hosts `com.getlokalapp.paymentsdk:*` (both the plugin
  marker artifacts and the libraries) must already be present in **both**
  `settings.gradle.kts` → `pluginManagement.repositories` **and**
  `dependencyResolutionManagement.repositories`. If it isn't, stop and tell the
  user — this is theirs to configure.
- **If Juspay is in scope**, add the Juspay Hyper SDK Maven repo to
  `dependencyResolutionManagement.repositories` (the `:juspay` module compiles
  against `in.juspay:hypersdk`):

  ```kotlin
  maven("https://maven.juspay.in/jp-build-packages/hyper-sdk/")
  ```

---

## 3. `settings.gradle.kts` — apply the settings plugin

The settings plugin runs at the **settings phase** to contribute the SDK's
build plumbing (SPI wiring, plugin pins). Add its id inside the existing
top-level `plugins {}` block of `settings.gradle.kts`:

```kotlin
plugins {
    // ...existing entries...
    id("com.getlokalapp.paymentsdk.lokal-payment-settings") version "0.0.1"
}
```

Also ensure this is present near the top of the file (the SDK relies on type-safe
project accessors being enabled in the demo; keep it if the host already uses it,
otherwise it's optional):

```kotlin
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
```

> Idempotency: if the `id(...)` line already exists, do nothing.

---

## 4. Version catalog — `gradle/libs.versions.toml`

**If the project uses a catalog**, add these entries (merge into the existing
`[versions]`, `[libraries]`, `[plugins]` tables — do not create duplicate
tables):

```toml
[versions]
lokalPaymentSdk = "0.0.1"

[libraries]
lokalpaymentsdk-shared            = { module = "com.getlokalapp.paymentsdk:shared",            version.ref = "lokalPaymentSdk" }
# --- add only the gateways in scope ---
lokalpaymentsdk-razorpay-checkout = { module = "com.getlokalapp.paymentsdk:razorpay-checkout", version.ref = "lokalPaymentSdk" }
lokalpaymentsdk-razorpay-customui = { module = "com.getlokalapp.paymentsdk:razorpay-customui", version.ref = "lokalPaymentSdk" }
lokalpaymentsdk-upi-intent        = { module = "com.getlokalapp.paymentsdk:upi-intent",        version.ref = "lokalPaymentSdk" }
lokalpaymentsdk-juspay            = { module = "com.getlokalapp.paymentsdk:juspay",            version.ref = "lokalPaymentSdk" }
lokalpaymentsdk-native-iap        = { module = "com.getlokalapp.paymentsdk:native-iap",        version.ref = "lokalPaymentSdk" }
lokalpaymentsdk-web-checkout      = { module = "com.getlokalapp.paymentsdk:web-checkout",      version.ref = "lokalPaymentSdk" }

[plugins]
lokalpaymentsdk-lokal-payment = { id = "com.getlokalapp.paymentsdk.lokal-payment", version.ref = "lokalPaymentSdk" }
```

> **No catalog?** Skip this section and instead inline coordinates in §5, e.g.
> `id("com.getlokalapp.paymentsdk.lokal-payment") version "0.0.1"` and
> `implementation("com.getlokalapp.paymentsdk:shared:0.0.1")`.

---

## 5. `<module>/build.gradle.kts` — plugin, iOS umbrella config, dependencies

### 5.1 Apply the per-module plugin

Inside the module's existing `plugins {}` block:

```kotlin
plugins {
    // ...existing (kotlin.multiplatform, compose, android library, etc.)...
    alias(libs.plugins.lokalpaymentsdk.lokal.payment) // or: id("com.getlokalapp.paymentsdk.lokal-payment") version "0.0.1"
}
```

### 5.2 Configure the iOS umbrella — **iOS hosts only**

The `com.getlokalapp.paymentsdk.lokal-payment` plugin generates the iOS SPM
package / XCFramework that the Xcode app consumes. Give it the framework name
(this name becomes the Swift `import` name on the host side — keep them identical):

```kotlin
val hostXcFrameworkName = "MyHostApp" // pick a stable name for THIS host

lokalPaymentSdk {
    xcFrameworkName = hostXcFrameworkName
}
```

And ensure the module actually produces that XCFramework from its iOS targets
(mirror the demo — merge into the existing `kotlin { }` block, don't duplicate
target declarations):

```kotlin
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

kotlin {
    val xcf = XCFramework(hostXcFrameworkName)
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = hostXcFrameworkName
            isStatic = true
            xcf.add(this)
        }
    }
    // ...existing androidLibrary/androidTarget, sourceSets, etc...
}
```

> **Android-only host:** omit §5.2 entirely (no `lokalPaymentSdk {}`, no
> XCFramework). The `lokal-payment` plugin's iOS work simply has nothing to do.

### 5.3 Add dependencies

Inside `kotlin { sourceSets { commonMain.dependencies { ... } } }`:

```kotlin
commonMain.dependencies {
    implementation(libs.lokalpaymentsdk.shared)          // REQUIRED — core runtime
    // --- gateways in scope only ---
    implementation(libs.lokalpaymentsdk.razorpay.checkout)
    implementation(libs.lokalpaymentsdk.razorpay.customui)
    implementation(libs.lokalpaymentsdk.upi.intent)
    implementation(libs.lokalpaymentsdk.juspay)
    implementation(libs.lokalpaymentsdk.native.iap)
    implementation(libs.lokalpaymentsdk.web.checkout)

    // Required to reference the SDK's public API types from host code: pay()
    // returns a Flow and PaymentOrder takes a JsonObject gatewayConfig, and
    // :shared exposes both via `implementation` (not inherited transitively).
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
```

> **Next: host-side usage.** This runbook stops at getting the SDK on the
> classpath. For the platform entry points and the glue that actually calls
> `LokalPaymentSdk.pay(...)`, see the usage companion
> [`host-glue-code.md`](./host-glue-code.md).

---

## 6. Verify

Do not declare done until this passes.

- **Android / common compile:**
  ```
  ./gradlew :<module>:assemble
  ```
- **iOS — generate Package.swift + XCFramework (iOS hosts):**
  - `Package.swift` is (re)generated at the **configuration phase** on *any*
    Gradle invocation/sync of `<module>` — there's no dedicated task and no
    Xcode run-script phase (the demo has none). A plain `./gradlew help` or an
    IDE Gradle sync is enough to refresh it.
  - The `.xcframework` the package points at (release variant) is built by the
    Kotlin convention task:
    ```
    ./gradlew :<module>:assemble<XcFrameworkName>ReleaseXCFramework
    ```
    e.g. for the demo: `:composeApp:assembleLokalPaymentSDKDemoReleaseXCFramework`.
  - Then build the Xcode app. Because Xcode consumes the **local** SPM package,
    it re-resolves the regenerated manifest on each build — no re-add needed
    after the initial one-time package setup.
- Launch the host, confirm `gatewayStatus()` lists the expected gateways, and
  run one `pay(...)` end to end for at least one gateway.

If the only failure is that `com.getlokalapp.paymentsdk:*` can't be resolved,
**stop and report** — that's the repo-access prerequisite from §2, not something
to work around.

---

## 7. Idempotency & troubleshooting checklist

- [ ] Every `id(...)`, catalog entry, and `implementation(...)` added only if
      absent.
- [ ] `xcFrameworkName` (§5.2) matches the Swift `import` name exactly.
- [ ] iOS steps skipped entirely for Android-only hosts.
- [ ] Juspay repo added **only** if Juspay is in scope.
- [ ] No `mavenLocal()` added.
- [ ] Compose (if any) added only to the **host**, never to an SDK module.
- [ ] Build passes (§6) before reporting completion.

---

## 8. Quick reference — minimal core integration (no gateways)

The smallest working wiring, for a KMP+iOS host with a catalog:

1. `settings.gradle.kts`: `id("com.getlokalapp.paymentsdk.lokal-payment-settings") version "0.0.1"`
2. `libs.versions.toml`: `lokalpaymentsdk-shared` library + `lokalpaymentsdk-lokal-payment` plugin
3. `<module>/build.gradle.kts`: apply `lokal-payment` plugin, set `xcFrameworkName`,
   add `XCFramework` binaries, `implementation(libs.lokalpaymentsdk.shared)`
4. Entry points: Android Activity hosts `App()`; iOS `MainViewController()` + Swift
   `import <xcFrameworkName>` via a local SPM package at `<module>/build/lokal/spmPackage`
5. Glue: `parseOrder` → `LokalPaymentSdk.pay(order).collect { render(it) }`

Add gateways later by dropping one catalog entry + one `implementation(...)` line
per gateway (and any gateway-specific init/repo). Nothing else changes.
