import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

// Bakes gradle/libs.versions.toml's juspay-pod-ios entry into a
// VENDOR_SDK_VERSION constant this plugin pins the host's podspec to — the same
// catalog entry :juspay links its cinterop bindings against, so the linked pod
// can't drift from the bindings the host consumes.
val generatePodVersion = registerVendorVersionTask(
    taskName = "generatePodVersion",
    packageName = "com.getlokalapp.paymentsdk.juspay.host",
    vendorSdkVersion = libs.versions.juspay.pod.ios.get(),
    asActual = false,
)

// Shared podspec-editing helper + generated pod-version constant, both compiled
// into this plugin jar (kept out of a published artifact so it stays self-contained).
sourceSets.main {
    kotlin.srcDir(rootProject.file("cocoapods-host-plugin-common/src/main/kotlin"))
    kotlin.srcDir(generatePodVersion)
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
