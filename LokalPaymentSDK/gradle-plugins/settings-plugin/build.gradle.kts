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
    // The settings-phase SPI LokalPaymentSettingsPlugin loads, plus each gateway's
    // settings contributor bundled onto the settings buildscript classpath for
    // ServiceLoader discovery. These (and everything they pull in) must resolve from
    // generic repos only — a contributor's job may be to ADD a vendor repo, so it
    // cannot itself need that repo to resolve.
    implementation(project(":gradle-plugins:settings-spi"))
    implementation(project(":gateways:juspay:settings-contributor"))
}

// A DEDICATED module, NOT folded into :gradle-plugins:cocoapods-host-plugin. Applying a
// Settings plugin puts its whole jar on the parent (settings) classpath, visible to
// every project. If this shared a jar with the `lokal-payment` PROJECT plugin, that
// project plugin would then be "already on the classpath with an unknown version"
// and a module could no longer apply `lokal-payment` with an explicit version
// (confirmed failure). Keeping the settings umbrella in its own jar avoids leaking
// the project plugin onto the settings classpath. It sits in :gradle-plugins
// (gateway-agnostic build plumbing) alongside :gradle-plugins:cocoapods-host-plugin
// and has no dependency on the :shared runtime library.
gradlePlugin {
    plugins {
        create("lokalPaymentSettings") {
            id = "com.getlokalapp.paymentsdk.lokal-payment-settings"
            implementationClass =
                "com.getlokalapp.paymentsdk.shared.LokalPaymentSettingsPlugin"
        }
    }
}
