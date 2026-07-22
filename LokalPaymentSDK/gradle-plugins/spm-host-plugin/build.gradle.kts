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
    // The shared SPM SPI (LokalGatewaySpmContributor + lokalPaymentSdkSpm extension)
    // LokalPaymentSpmPlugin loads and dispatches to.
    implementation(project(":gradle-plugins:spm-host-spi"))
    // Bundle each gateway's SPM host contributor onto the buildscript classpath so
    // LokalPaymentSpmPlugin can discover it via ServiceLoader. Self-gates to a no-op
    // unless the host imports its gateway module.
    implementation(project(":gateways:razorpay-checkout:spm-host-contributor"))
    implementation(project(":gateways:native-iap:spm-host-contributor"))
}

// This module lives in :gradle-plugins (gateway-agnostic build plumbing), parallel
// to :gradle-plugins:cocoapods-host-plugin — see
// docs/cocoapods-to-spm-migration-plan.md (D5): a host picks ONE of `lokal-payment`
// (CocoaPods) or `lokal-payment-spm` (this one), rather than one plugin branching on
// a runtime flag.
//
// Only `lokal-payment-spm` is exposed as an applicable plugin: the SPM-flavored
// umbrella. It creates the `lokalPaymentSdkSpm { }` DSL, dispatches to each
// gateway's SPM host contributor via ServiceLoader, and generates a local
// Package.swift wrapping the host's own XCFramework plus each contributed vendor
// SPM package.
gradlePlugin {
    plugins {
        create("lokalPaymentSpm") {
            id = "com.getlokalapp.paymentsdk.lokal-payment-spm"
            implementationClass =
                "com.getlokalapp.paymentsdk.shared.LokalPaymentSpmPlugin"
        }
    }
}
