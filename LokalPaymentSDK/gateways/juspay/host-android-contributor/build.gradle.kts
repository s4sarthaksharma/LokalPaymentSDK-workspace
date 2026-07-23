import com.getlokalapp.paymentsdk.buildsrc.registerOwnedModuleTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

// Gateway-specific group so the bare `host-android-contributor` artifactId doesn't
// collide with any other gateway's Android contributor — mirrors the sibling
// :gateways:juspay:host-contributor.
group = "com.getlokalapp.paymentsdk.juspay"

kotlin {
    jvmToolchain(11)
}

dependencies {
    // The Android host SPI (LokalGatewayHostAndroidContributor) this contributor
    // implements. Depended on rather than srcDir'd so exactly one copy of the SPI
    // type exists across the umbrella plugin and every contributor.
    implementation(project(":gradle-plugins:host-android-spi"))
    // Juspay's own Gradle plugin, applied eagerly on the host's application module by
    // JuspayHostAndroidContributor. It injects the HyperSDK runtime jars + merchant
    // assets into the APK, which is why :juspay can compile HyperSDK `compileOnly`.
    implementation("in.juspay:hypersdk.plugin:2.0.6")
    // Gradle API for org.gradle.api.* used by the contributor; provided by the Gradle
    // runtime on the host's buildscript classpath, never a published dep.
    compileOnly(gradleApi())
}

// Bakes the owning gateway module name (this module's parent, `juspay`) into an
// OWNED_MODULE constant, so JuspayHostAndroidContributor.module can't drift from the
// artifactId the umbrella plugin gates on. Same codegen idiom as the iOS host-contributor.
val generateOwnedModule = registerOwnedModuleTask(
    taskName = "generateOwnedModule",
    packageName = "com.getlokalapp.paymentsdk.juspay.host",
)

sourceSets.main {
    kotlin.srcDir(generateOwnedModule)
}

// Not a Gradle plugin a host applies (no `java-gradle-plugin`): a plain contributor jar
// the umbrella `com.getlokalapp.paymentsdk.lokal-payment-android` plugin bundles
// and discovers via ServiceLoader (see JuspayHostAndroidContributor). Published at its
// module coordinate so the umbrella's POM can pull it — and hypersdk.plugin transitively
// — onto the host's buildscript classpath from Maven.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
