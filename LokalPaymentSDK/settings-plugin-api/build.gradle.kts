plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

dependencies {
    // The SPI references org.gradle.api.initialization.Settings; the Gradle runtime
    // provides it on the host's settings buildscript classpath, so it must never
    // become a published POM dep.
    compileOnly(gradleApi())
}

// The shared contract between the umbrella
// `com.getlokalapp.paymentsdk.lokal-payment-settings` plugin and every gateway
// settings contributor. Published as a plain jar (not a Gradle plugin) so both
// sides depend on ONE copy of the SPI types — srcDir'ing the interface into both
// would put two identically-named classes on the compile classpath and fail to
// compile. Mirrors :cocoapods-host-plugin-api, but for the settings phase.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
