plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

// A host applies this to its Compose/KMP module (the one that owns the iOS
// `cocoapods {}` block and produces the umbrella framework). It appends
// `spec.dependency 'HyperSDK'` to that module's generated podspec so the vendor
// pod is pulled transitively from the CocoaPods trunk — the host never names
// HyperSDK in its Podfile. Deliberately does NOT add a `pod(...)` cinterop to
// the host module: the Kotlin bindings already ride in via the published
// :juspay klib (Maven), and adding a cinterop here would drag the host's iOS
// compile through HyperSDK's synthetic-build "Validate Mandatory Files" gate.
// The one thing this can't cover is the Podfile `post_install` Fuse.rb step
// (merchant-asset download) — Gradle can't inject a Podfile hook, so that stays
// in the host Podfile alongside MerchantConfig.txt (the host's clientId).
gradlePlugin {
    plugins {
        create("juspayCocoapodsHost") {
            id = "com.getlokalapp.paymentsdk.juspay-cocoapods-host"
            implementationClass =
                "com.getlokalapp.paymentsdk.juspay.host.JuspayCocoapodsHostPlugin"
        }
    }
}
