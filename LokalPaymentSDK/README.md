# LokalPaymentSDK

Kotlin Multiplatform (KMP) payment SDK library for Lokal apps. This repo
contains **only the library** — public entry point `LokalPaymentSdk`,
package `com.getlokalapp.paymentsdk`.

Sample/consumer apps live in a separate sibling project,
[`LokalPaymentSDKDemo`](../LokalPaymentSDKDemo), which depends on this
library the way a real host app would: via a published artifact
(`mavenLocal()` for now), not a Gradle project() reference.

## Structure

- `shared/` — the gateway-agnostic KMP core (Android + iOS).
- `webview/` — reusable secure WebView bridge/session support.
- `gateways/` — opt-in gateway modules (Razorpay, UPI Intent, Juspay, Web
  Checkout, Native IAP, and others as added). Include only the gateways the
  host uses.
- `gradle-plugins/` — host/build integration plugins used to assemble the
  Android and iOS SDK artifacts.

## Toolchain

Kotlin 2.4.0 · AGP 9.2.1 · compileSdk 37 · minSdk 24.

## Publishing locally

After any change to `shared`, publish it so `LokalPaymentSDKDemo` (or any
other consumer) picks it up:

```bash
./gradlew :shared:publishToMavenLocal
```

This publishes under `com.getlokalapp.paymentsdk` (group) — check
`~/.m2/repository/com/getlokalapp/paymentsdk/` for the exact artifact IDs
produced (the Android-specific one is what a plain Android app depends
on; a bare `com.getlokalapp.paymentsdk:shared:<version>` reference is the
Kotlin metadata module, not directly usable from a non-KMP consumer).

Consumers resolving from `mavenLocal()` won't see a new version unless
you bump `version` in `shared/build.gradle.kts` or force a refresh
(`--refresh-dependencies`) — Gradle caches resolved dependencies.

## Testing

```bash
./gradlew :shared:allTests
```

## iOS

iOS integration is Swift Package Manager-based. The host integration plugin
assembles the selected gateway klibs and vendor dependencies into the host's
generated umbrella package. CocoaPods integration is retired; see
[`docs/integrating-the-sdk.md`](docs/integrating-the-sdk.md) for the current
SPM setup.
