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
    // The Android host SPI LokalPaymentHostAndroidPlugin loads, plus each gateway's
    // Android host contributor bundled onto the buildscript classpath for ServiceLoader
    // discovery — exactly how :gradle-plugins:host-plugin bundles the iOS host
    // contributors. Each contributor carries its own vendor plugin (e.g.
    // in.juspay:hypersdk.plugin) transitively, so those land on the app module's
    // buildscript classpath only when this plugin is applied — never on the iOS host.
    implementation(project(":gradle-plugins:host-android-spi"))
    implementation(project(":gateways:juspay:host-android-contributor"))
}

// This module lives in :gradle-plugins (gateway-agnostic build plumbing). It's the sole
// Android umbrella plugin: `com.getlokalapp.paymentsdk.lokal-payment-android`,
// applied on the host's `com.android.application` module. The project-phase Android
// twin of `com.getlokalapp.paymentsdk.lokal-payment` (iOS): it discovers each gateway's
// Android host contributor via ServiceLoader and dispatches to it. Kept a SEPARATE jar
// from :gradle-plugins:host-plugin so vendor Android plugins (hypersdk.plugin, …) that
// the contributors pull in never leak onto the iOS host's buildscript classpath.
gradlePlugin {
    plugins {
        create("lokalPaymentHostAndroid") {
            id = "com.getlokalapp.paymentsdk.lokal-payment-android"
            implementationClass =
                "com.getlokalapp.paymentsdk.shared.LokalPaymentHostAndroidPlugin"
        }
    }
}
