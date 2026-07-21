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

// The shared contract between the umbrella `com.getlokalapp.paymentsdk.lokal-payment`
// plugin and every gateway host contributor. Published as a plain jar (not a Gradle
// plugin) so both sides depend on ONE copy of the SPI types — srcDir'ing the
// interface into both would put two identically-named classes on the compile
// classpath and fail to compile.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
