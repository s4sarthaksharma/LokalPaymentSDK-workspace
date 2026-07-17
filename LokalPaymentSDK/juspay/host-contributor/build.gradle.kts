import com.getlokalapp.paymentsdk.buildsrc.registerVendorVersionTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

// Gateway-specific group so the bare `host-contributor` artifactId doesn't collide
// with other gateways' contributors (e.g. razorpay's) — each gateway namespaces its
// own; classification is by the module path / group, not an artifactId prefix.
group = "com.getlokalapp.paymentsdk.juspay"

kotlin {
    jvmToolchain(11)
}

dependencies {
    // The shared SPI (LokalGatewayHostContributor + lokalPaymentSdk extension) this
    // contributor implements. Depended on rather than srcDir'd so exactly one copy
    // of the SPI types exists across the umbrella plugin and every contributor.
    implementation(project(":cocoapods-host-plugin-api"))
    // Gradle API for org.gradle.api.* used by the contributor; provided by the
    // Gradle runtime on the host's buildscript classpath, never a published dep.
    compileOnly(gradleApi())
}

// Bakes gradle/libs.versions.toml's juspay-pod-ios entry into a VENDOR_SDK_VERSION
// constant this contributor pins the host's podspec to — the same catalog entry
// :juspay links its cinterop bindings against, so the linked pod can't drift from
// the bindings the host consumes.
val generatePodVersion = registerVendorVersionTask(
    taskName = "generatePodVersion",
    packageName = "com.getlokalapp.paymentsdk.juspay.host",
    vendorSdkVersion = libs.versions.juspay.pod.ios.get(),
    asActual = false,
)

// Shared podspec-editing helper + generated pod-version constant, both compiled
// into this jar (kept out of a published artifact so it stays self-contained).
sourceSets.main {
    kotlin.srcDir(rootProject.file("cocoapods-host-plugin-common/src/main/kotlin"))
    kotlin.srcDir(generatePodVersion)
}

// No longer a Gradle plugin a host applies: it's a plain contributor jar the
// umbrella `com.getlokalapp.paymentsdk.lokal-payment` plugin bundles and discovers
// via ServiceLoader (see JuspayHostContributor). It appends
// `spec.dependency 'HyperSDK'` to the host module's generated podspec so the vendor
// pod is pulled transitively from the CocoaPods trunk — the host never names
// HyperSDK in its Podfile. Published at its module coordinate so the umbrella's POM
// can pull it onto the host's buildscript classpath from Maven.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
