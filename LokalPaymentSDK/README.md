# LokalPaymentSDK

Kotlin Multiplatform (KMP) payment SDK library for Lokal apps. This repo
contains **only the library** — public entry point `LokalPaymentSdk`,
package `com.getlokalapp.paymentsdk`.

Sample/consumer apps live in a separate sibling project,
[`LokalPaymentSDKDemo`](../LokalPaymentSDKDemo), which depends on this
library the way a real host app would: via a published artifact
(`mavenLocal()` for now), not a Gradle project() reference.

## Structure

- `shared/` — the KMP library (Android + iOS). Common code in
  `commonMain`, platform code in `androidMain` / `iosMain`. Publishes an
  Android artifact to Maven and a `Shared` CocoaPod for iOS.

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

This module is CocoaPods-integrated (`org.jetbrains.kotlin.native.cocoapods`).
A consuming iOS app needs a `Podfile` pointing at this module's `shared/`
directory, e.g. (from `LokalPaymentSDKDemo/iosApp/Podfile`):

```ruby
pod 'Shared', :path => '../../LokalPaymentSDK/shared'
```
