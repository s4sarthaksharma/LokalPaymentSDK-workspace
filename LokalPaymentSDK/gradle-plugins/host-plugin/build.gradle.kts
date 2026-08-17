import com.getlokalapp.paymentsdk.buildsrc.registerModuleVersionTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

// Bakes this plugin's own version (root gradle.properties) into the source LokalPaymentPlugin
// uses for the gateway coordinates it adds from `lokalPaymentSdk { gateways = … }`. Same
// pattern as :shared's PAYMENT_SDK_VERSION and every gateway's MODULE_VERSION. This is what
// makes the plugin version the single knob: the gateway artifacts a host resolves are always
// the ones published alongside the plugin it applied, with no host-side version to drift.
val generateModuleVersion = registerModuleVersionTask(
    taskName = "generateModuleVersion",
    packageName = "com.getlokalapp.paymentsdk.shared",
    constName = "SDK_VERSION",
)

sourceSets.main {
    kotlin.srcDir(generateModuleVersion)
}

dependencies {
    // The shared SPM SPI (LokalGatewayHostContributor + lokalPaymentSdk extension)
    // LokalPaymentPlugin loads and dispatches to.
    implementation(project(":gradle-plugins:host-spi"))
    // Bundle each gateway's SPM host contributor onto the buildscript classpath so
    // LokalPaymentPlugin can discover it via ServiceLoader. Self-gates to a no-op
    // unless the host imports its gateway module.
    implementation(project(":gateways:razorpay-checkout:host-contributor"))
    implementation(project(":gateways:native-iap:host-contributor"))
    implementation(project(":gateways:juspay:host-contributor"))
    // Read-only use of the KMP extension: whether the applying module has Apple targets, which
    // decides if the iOS half runs (an Android-only host applies this plugin purely to select
    // gateways). compileOnly — see the catalog entry — since the host supplies it by applying
    // the KMP plugin, and it must not become a POM dependency of this plugin.
    compileOnly(libs.kotlin.gradle.plugin)
}

// This module lives in :gradle-plugins (gateway-agnostic build plumbing). It's the sole
// iOS umbrella plugin: `com.getlokalapp.paymentsdk.lokal-payment`, applied on the host
// module that assembles the umbrella XCFramework. (It took over the plain `lokal-payment`
// id when the CocoaPods umbrella was removed in Phase 3 — see
// docs/cocoapods-to-spm-migration-plan.md.) It creates the `lokalPaymentSdk { }` DSL,
// dispatches to each gateway's SPM host contributor via ServiceLoader, and generates a
// local Package.swift wrapping the host's own XCFramework plus each contributed vendor
// SPM package / first-party source target.
gradlePlugin {
    plugins {
        create("lokalPayment") {
            id = "com.getlokalapp.paymentsdk.lokal-payment"
            implementationClass =
                "com.getlokalapp.paymentsdk.shared.LokalPaymentPlugin"
        }
    }
}
