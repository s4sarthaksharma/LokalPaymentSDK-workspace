plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
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
