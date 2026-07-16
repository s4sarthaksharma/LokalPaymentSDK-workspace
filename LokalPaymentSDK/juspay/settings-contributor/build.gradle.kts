plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

// Deliberately no dependency on in.juspay:hypersdk.plugin or anything else
// juspay-specific: this contributor's whole job is to add in.juspay's Maven repo to
// a host's settings.gradle.kts, so it must itself resolve via generic repos only
// (mavenLocal here) — otherwise loading it would need the very repo it exists to add.
dependencies {
    // The settings-phase SPI (LokalGatewaySettingsContributor) this contributor
    // implements. Depended on rather than srcDir'd so exactly one copy of the SPI
    // types exists across the umbrella plugin and every contributor.
    implementation(project(":settings-plugin-api"))
    // Gradle API for org.gradle.api.initialization.Settings used by the contributor;
    // provided by the Gradle runtime on the host's settings classpath, never a
    // published dep.
    compileOnly(gradleApi())
}

// A plain contributor jar, NOT a Gradle plugin (no `java-gradle-plugin`): it's
// discovered via ServiceLoader by the umbrella `lokal-payment-settings` plugin, never
// applied by id.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

// Bakes this build's version (root gradle.properties) into the jar so
// JuspaySettingsContributor can register it as the pluginManagement default for the
// sibling juspay-host plugin — both publish at the same version.
val generateVersionResource by tasks.registering {
    val pluginVersion = version.toString()
    val outDir = layout.buildDirectory.dir("generated/versionResource")
    inputs.property("pluginVersion", pluginVersion)
    outputs.dir(outDir)
    doLast {
        outDir.get().asFile.resolve("lokalpaymentsdk-plugin-version.txt").writeText(pluginVersion)
    }
}

sourceSets.main {
    resources.srcDir(generateVersionResource)
}
