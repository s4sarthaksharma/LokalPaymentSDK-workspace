plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

dependencies {
    // The SPI references org.gradle.api.Project; the Gradle runtime provides it on
    // the host's buildscript classpath, so it must never become a published POM dep.
    compileOnly(gradleApi())
}

// The Android sibling of :gradle-plugins:host-spi — the shared contract between the
// umbrella `com.getlokalapp.paymentsdk.lokal-payment-android` plugin and every
// gateway's Android host contributor. Kept as its own module (not folded into
// host-spi) so the iOS and Android umbrellas stay fully independent — the Android
// contributors pull in `hypersdk.plugin`, which must never leak onto the iOS host's
// classpath. Published as a plain jar so both sides depend on ONE copy of the SPI type.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
