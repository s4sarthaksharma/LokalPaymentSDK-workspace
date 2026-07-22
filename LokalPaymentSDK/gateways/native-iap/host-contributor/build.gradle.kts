plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

// Gateway-specific group so the bare `host-contributor` artifactId doesn't
// collide with other gateways' contributors — mirrors :razorpay-checkout's.
group = "com.getlokalapp.paymentsdk.nativeiap"

kotlin {
    jvmToolchain(11)
}

dependencies {
    // The shared SPM SPI (LokalGatewayHostContributor + lokalPaymentSdk extension)
    // this contributor implements.
    implementation(project(":gradle-plugins:host-spi"))
    // Gradle API for org.gradle.api.* used by the contributor; provided by the
    // Gradle runtime on the host's buildscript classpath, never a published dep.
    compileOnly(gradleApi())
}

// No generatePackageVersion task like :razorpay-checkout's contributor: native-iap has
// no versioned vendor package to pin. Its contribution is first-party Swift source
// (NativeIapBridge), resolved from :native-iap's own `iossrc` Maven artifact at the
// dependency's own version — so there's nothing to bake in here.

// Not a Gradle plugin a host applies: a plain contributor jar the umbrella
// `com.getlokalapp.paymentsdk.lokal-payment` plugin bundles and discovers via
// ServiceLoader (see NativeIapHostContributor). Published at its module coordinate so
// the umbrella's POM can pull it onto the host's buildscript classpath from Maven.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
