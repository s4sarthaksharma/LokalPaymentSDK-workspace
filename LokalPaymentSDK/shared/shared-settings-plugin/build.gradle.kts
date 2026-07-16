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
    implementation(project(":settings-plugin-api"))
    implementation(project(":juspay:settings-contributor"))
}

// A DEDICATED module, NOT folded into :shared:shared-cocoapods-plugin. Applying a
// Settings plugin puts its whole jar on the parent (settings) classpath, visible to
// every project. If this shared a jar with the `lokal-payment` PROJECT plugin, that
// project plugin would then be "already on the classpath with an unknown version"
// and a module could no longer apply `lokal-payment` with an explicit version
// (confirmed failure). Keeping the settings umbrella in its own jar avoids leaking
// the project plugin onto the settings classpath. It has no dependency on the
// :shared library — the nesting under :shared is organisational, mirroring
// :shared:shared-cocoapods-plugin.
gradlePlugin {
    plugins {
        create("lokalPaymentSettings") {
            id = "com.getlokalapp.paymentsdk.lokal-payment-settings"
            implementationClass =
                "com.getlokalapp.paymentsdk.shared.LokalPaymentSettingsPlugin"
        }
    }
}
