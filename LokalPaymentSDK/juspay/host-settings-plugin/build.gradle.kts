plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.getlokalapp.paymentsdk"

kotlin {
    jvmToolchain(11)
}

// Deliberately no dependency on in.juspay:hypersdk.plugin or anything else
// juspay-specific: this plugin's whole job is to add in.juspay's Maven repo to
// a host's settings.gradle.kts, so it must itself resolve via generic repos
// only (mavenLocal here) — otherwise applying it would need the very repo it
// exists to add.
gradlePlugin {
    plugins {
        create("juspayHostSettings") {
            id = "com.getlokalapp.paymentsdk.juspay-host-settings"
            implementationClass = "com.getlokalapp.paymentsdk.juspay.host.JuspayHostSettingsPlugin"
        }
    }
}

// Bakes this build's version (root gradle.properties) into the jar so
// JuspayHostSettingsPlugin can register it as the pluginManagement default for
// the sibling juspay-host plugin — both publish at the same version.
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
