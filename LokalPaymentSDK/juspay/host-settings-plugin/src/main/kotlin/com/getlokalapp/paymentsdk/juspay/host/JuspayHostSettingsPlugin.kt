package com.getlokalapp.paymentsdk.juspay.host

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

class JuspayHostSettingsPlugin : Plugin<Settings> {

    override fun apply(settings: Settings) {
        settings.pluginManagement.repositories.maven { it.setUrl(JUSPAY_MAVEN_URL) }
        settings.dependencyResolutionManagement.repositories.maven { it.setUrl(JUSPAY_MAVEN_URL) }
        // Default version for the sibling project plugin, so the host's app
        // module can write `plugins { id("com.getlokalapp.paymentsdk.juspay-host") }`
        // (or a versionless catalog alias) with no version anywhere — this
        // settings plugin's own version (pinned once in the host's
        // settings.gradle.kts) is the only Juspay-related version a host ever
        // declares.
        settings.pluginManagement.plugins.id(HOST_PLUGIN_ID).version(hostPluginVersion())
    }

    // Both wrapper plugins publish from the same build at the same version
    // (root gradle.properties), baked into this jar by generateVersionResource.
    private fun hostPluginVersion(): String =
        checkNotNull(javaClass.getResourceAsStream("/$VERSION_RESOURCE")) {
            "$VERSION_RESOURCE missing from the juspay-host-settings plugin jar"
        }.bufferedReader().use { it.readText().trim() }

    private companion object {
        // in.juspay's own Maven repo, needed both to resolve hypersdk.plugin (a
        // transitive dependency of JuspayHostPlugin, via pluginManagement) and
        // for the runtime jars/merchant assets hypersdk.plugin injects into
        // whichever project applies it (via dependencyResolutionManagement).
        const val JUSPAY_MAVEN_URL = "https://maven.juspay.in/jp-build-packages/hyper-sdk/"

        const val HOST_PLUGIN_ID = "com.getlokalapp.paymentsdk.juspay-host"

        const val VERSION_RESOURCE = "lokalpaymentsdk-plugin-version.txt"
    }
}
