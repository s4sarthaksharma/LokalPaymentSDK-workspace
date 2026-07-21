package com.getlokalapp.paymentsdk.juspay.host

import com.getlokalapp.paymentsdk.host.LokalGatewaySettingsContributor
import org.gradle.api.initialization.Settings

/**
 * Juspay's settings-phase contribution to a host build: adds in.juspay's Maven repo
 * and pins a default version for the sibling `juspay-android-host` project plugin.
 * Discovered by the umbrella `com.getlokalapp.paymentsdk.lokal-payment-settings` plugin
 * via ServiceLoader.
 *
 * Contributes unconditionally (settings phase can't see the module dependency graph;
 * see [LokalGatewaySettingsContributor]): an unused Maven repo and a pin for a plugin
 * that's never applied are both no-ops. The real gating is at the project level —
 * nothing Juspay happens unless the host applies `juspay-android-host` / imports
 * `:juspay`.
 */
class JuspaySettingsContributor : LokalGatewaySettingsContributor {

    override fun contribute(settings: Settings) {
        settings.pluginManagement.repositories.maven { it.setUrl(JUSPAY_MAVEN_URL) }
        settings.dependencyResolutionManagement.repositories.maven { it.setUrl(JUSPAY_MAVEN_URL) }
        // Default version for the sibling Android project plugin, so the host's app
        // module can write `plugins { id("com.getlokalapp.paymentsdk.juspay-android-host") }`
        // (or a versionless catalog alias) with no version anywhere — the umbrella
        // settings plugin's own version (pinned once in the host's settings.gradle.kts)
        // is the only Juspay-related version a host ever declares.
        settings.pluginManagement.plugins.id(ANDROID_HOST_PLUGIN_ID).version(hostPluginVersion())
    }

    // Both the umbrella and the juspay-android-host plugin publish from the same build at the
    // same version (root gradle.properties), baked into this jar by
    // generateVersionResource.
    private fun hostPluginVersion(): String =
        checkNotNull(javaClass.getResourceAsStream("/$VERSION_RESOURCE")) {
            "$VERSION_RESOURCE missing from the juspay settings-contributor jar"
        }.bufferedReader().use { it.readText().trim() }

    private companion object {
        // in.juspay's own Maven repo, needed both to resolve hypersdk.plugin (a
        // transitive dependency of JuspayAndroidHostPlugin, via pluginManagement) and for
        // the runtime jars/merchant assets hypersdk.plugin injects into whichever project
        // applies it (via dependencyResolutionManagement).
        const val JUSPAY_MAVEN_URL = "https://maven.juspay.in/jp-build-packages/hyper-sdk/"

        const val ANDROID_HOST_PLUGIN_ID = "com.getlokalapp.paymentsdk.juspay-android-host"

        const val VERSION_RESOURCE = "lokalpaymentsdk-plugin-version.txt"
    }
}
