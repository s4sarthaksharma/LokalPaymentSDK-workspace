# LokalPaymentSDKDemo

Sample consumer apps for [`LokalPaymentSDK`](../LokalPaymentSDK), used to
verify the library the way a real host app would integrate it — via a
published Maven artifact / CocoaPod, not a Gradle project() reference or
same-repo module.

## Structure

- `androidApp/` — native Android app (Jetpack Compose) that depends on
  `com.getlokalapp.paymentsdk:shared-android` from `mavenLocal()`.
- `iosApp/` — native SwiftUI app that imports the `Shared` CocoaPod, built
  from `../LokalPaymentSDK/shared` via a local path dependency in `Podfile`.

## Before building

`LokalPaymentSDK` must be published locally first:

```bash
cd ../LokalPaymentSDK
./gradlew :shared:publishToMavenLocal
```

Re-run that after every change to the library — this project resolves it
from `mavenLocal()`, which won't auto-refresh (bump `version` in
`LokalPaymentSDK/shared/build.gradle.kts`, or pass
`--refresh-dependencies` here, if changes don't seem to show up).

## Build

Android app:

```bash
./gradlew :androidApp:assembleDebug
```

iOS: run `pod install` inside `iosApp/` (after the library above has been
published/built at least once), then open `iosApp/iosApp.xcworkspace` —
**not** the `.xcodeproj` — in Xcode and run.
