import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

// Gateway-specific group so the bare `spm-host-contributor` artifactId doesn't
// collide with other gateways' contributors — mirrors :host-contributor's group.
group = "com.getlokalapp.paymentsdk.razorpay"

kotlin {
    jvmToolchain(11)
}

dependencies {
    // The shared SPM SPI (LokalGatewaySpmContributor + lokalPaymentSdkSpm extension)
    // this contributor implements.
    implementation(project(":gradle-plugins:spm-host-spi"))
    // Gradle API for org.gradle.api.* used by the contributor; provided by the
    // Gradle runtime on the host's buildscript classpath, never a published dep.
    compileOnly(gradleApi())
}

// Bakes gradle/libs.versions.toml's razorpay-pod-ios entry into a
// VENDOR_SDK_VERSION constant this contributor pins the generated Package.swift's
// `.package(url:, exact:)` to — the same catalog entry :razorpay-checkout links its
// cinterop bindings against, so the linked package can't drift from the bindings
// the host consumes. Same mechanism as :host-contributor's generatePodVersion, kept
// in its own package (com.getlokalapp.paymentsdk.razorpay.spmhost, not .host) so the
// two contributor jars never collide if both ever end up on one classpath.
val generatePackageVersion = registerVendorVersionTask(
    taskName = "generatePackageVersion",
    packageName = "com.getlokalapp.paymentsdk.razorpay.spmhost",
    vendorSdkVersion = libs.versions.razorpay.pod.ios.get(),
    asActual = false,
)

sourceSets.main {
    kotlin.srcDir(generatePackageVersion)
}

// Not a Gradle plugin a host applies: a plain contributor jar the umbrella
// `com.getlokalapp.paymentsdk.lokal-payment-spm` plugin bundles and discovers via
// ServiceLoader (see RazorpaySpmContributor). Published at its module coordinate so
// the umbrella's POM can pull it onto the host's buildscript classpath from Maven.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
