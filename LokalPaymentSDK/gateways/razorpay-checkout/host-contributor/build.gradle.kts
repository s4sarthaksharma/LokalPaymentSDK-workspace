import com.getlokalapp.paymentsdk.buildsrc.registerOwnedModuleTask
import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

// Gateway-specific group so the bare `host-contributor` artifactId doesn't
// collide with other gateways' contributors — mirrors :host-contributor's group.
group = "com.getlokalapp.paymentsdk.razorpay"

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

// Bakes gradle/libs.versions.toml's razorpay-spm-ios entry into a
// VENDOR_SDK_VERSION constant this contributor pins the generated Package.swift's
// `.package(url:, exact:)` to — the same catalog entry :razorpay-checkout links its
// cinterop bindings against, so the linked package can't drift from the bindings
// the host consumes. Same mechanism as :host-contributor's generatePodVersion, kept
// in its own package (com.getlokalapp.paymentsdk.razorpay.host, not .host) so the
// two contributor jars never collide if both ever end up on one classpath.
val generatePackageVersion = registerVendorVersionTask(
    taskName = "generatePackageVersion",
    packageName = "com.getlokalapp.paymentsdk.razorpay.host",
    vendorSdkVersion = libs.versions.razorpay.spm.ios.get(),
    asActual = false,
)

// Bakes the owning gateway module name (this module's parent, `razorpay-checkout`) into an
// OWNED_MODULE constant, so RazorpayHostContributor.module can't drift from the artifactId
// the umbrella plugin gates on. Same codegen idiom as generatePackageVersion above.
val generateOwnedModule = registerOwnedModuleTask(
    taskName = "generateOwnedModule",
    packageName = "com.getlokalapp.paymentsdk.razorpay.host",
)

sourceSets.main {
    kotlin.srcDir(generatePackageVersion)
    kotlin.srcDir(generateOwnedModule)
}

// Not a Gradle plugin a host applies: a plain contributor jar the umbrella
// `com.getlokalapp.paymentsdk.lokal-payment` plugin bundles and discovers via
// ServiceLoader (see RazorpayHostContributor). Published at its module coordinate so
// the umbrella's POM can pull it onto the host's buildscript classpath from Maven.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
