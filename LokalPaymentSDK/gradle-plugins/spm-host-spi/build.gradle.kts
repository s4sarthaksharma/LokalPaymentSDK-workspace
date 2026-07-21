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

// The SPM-flavored sibling of :gradle-plugins:cocoapods-host-spi — the shared
// contract between the umbrella `com.getlokalapp.paymentsdk.lokal-payment-spm`
// plugin and every gateway's SPM host contributor. Kept as its own module (not
// folded into cocoapods-host-spi) so the two plugins stay fully independent — a
// host applies one or the other, never both (see
// docs/cocoapods-to-spm-migration-plan.md, D5). Published as a plain jar so both
// sides depend on ONE copy of the SPI types.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
