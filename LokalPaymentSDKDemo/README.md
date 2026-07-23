# LokalPaymentSDKDemo

Sample consumer apps for [`LokalPaymentSDK`](../LokalPaymentSDK), used to verify the
library the way a real host app integrates it — via published Maven artifacts (Android)
and a generated local Swift package (iOS), not same-repo `project()` references.

## Structure

- **`composeApp/`** — the shared KMP module (Compose Multiplatform UI). Depends on the SDK
  gateway klibs from `mavenLocal()` and applies the SDK's `com.getlokalapp.paymentsdk.lokal-payment`
  plugin, which compiles everything into one iOS **`LokalPaymentSDKDemo.xcframework`** and
  generates a local Swift package at `composeApp/build/lokal/spmPackage/`.
- **`androidApp/`** — native Android app (Jetpack Compose) depending on the SDK from `mavenLocal()`.
- **`iosApp/`** — native SwiftUI app. Consumes the generated Swift package above (already wired
  into `iosApp.xcodeproj` and committed — no per-clone Xcode setup) and `import LokalPaymentSDKDemo`.

## Before building

`LokalPaymentSDK` must be published to your local Maven repo first — this project resolves it
from `mavenLocal()`:

```bash
cd ../LokalPaymentSDK
./gradlew publishToMavenLocal
```

Re-run that after every change to the library; `mavenLocal()` won't auto-refresh (bump the SDK
`version`, or pass `--refresh-dependencies` here, if changes don't show up).

> The demo includes the **Juspay** gateway, whose build fails fast unless the `juspayClientId`
> Gradle property is set — it's already configured in this project's `gradle.properties`.

## Build

**Android:**

```bash
./gradlew :androidApp:assembleDebug
```

**iOS:** assemble the framework (which also regenerates the Swift package), then open the
project — the plain `.xcodeproj`, there is no `.xcworkspace`/CocoaPods anymore — and run:

```bash
./gradlew :composeApp:assembleLokalPaymentSDKDemoReleaseXCFramework
open iosApp/iosApp.xcodeproj
```

Re-run that Gradle task after any Kotlin change; Xcode picks up the refreshed binary on the
next build (SPM binary targets are prebuilt — no per-build Gradle step). See the generated
`composeApp/build/lokal/spmPackage/INTEGRATION.md` for the exact wiring values.

## Integrating the SDK into your own app

This demo is the worked example; the full, general integration guide is
[`LokalPaymentSDK/docs/integrating-the-sdk.md`](../LokalPaymentSDK/docs/integrating-the-sdk.md)
(Gradle setup, XcodeGen/Tuist wiring, and per-gateway steps).
