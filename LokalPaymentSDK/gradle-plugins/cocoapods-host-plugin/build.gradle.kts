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
    // The shared SPI (LokalGatewayHostContributor + lokalPaymentSdk extension)
    // LokalPaymentPlugin loads and dispatches to.
    implementation(project(":gradle-plugins:cocoapods-host-spi"))
    // Bundle each gateway's host contributor onto the buildscript classpath so
    // LokalPaymentPlugin can discover it via ServiceLoader. Each contributor
    // self-gates to a no-op unless the host imports its gateway module, so
    // depending on all of them here does not make an unused gateway do anything.
    implementation(project(":gateways:razorpay-checkout:host-contributor"))
    implementation(project(":gateways:juspay:host-contributor"))
}

// This module lives in :gradle-plugins (gateway-agnostic build plumbing) rather than
// under any one gateway or the :shared runtime library, which it has no dependency on.
//
// Only `lokal-payment` is exposed as an applicable plugin: the single host-facing
// umbrella. It creates the `lokalPaymentSdk { }` DSL, dispatches to each gateway's
// host contributor (razorpay, …) via ServiceLoader, AND folds in
// SharedCocoapodsPlugin (first-party `iossrc` pod plumbing + Podfile management) by
// applying it directly. SharedCocoapodsPlugin has no standalone id — it's an
// implementation detail applied by class from LokalPaymentPlugin.
gradlePlugin {
    plugins {
        create("lokalPayment") {
            id = "com.getlokalapp.paymentsdk.lokal-payment"
            implementationClass =
                "com.getlokalapp.paymentsdk.shared.LokalPaymentPlugin"
        }
    }
}
