package com.getlokalapp.paymentsdk.juspay.host

import com.getlokalapp.paymentsdk.host.LokalGatewaySettingsContributor
import org.gradle.api.initialization.Settings

/**
 * Juspay's settings-phase contribution to a host build: adds in.juspay's Maven repo so
 * `hypersdk.plugin` (and the runtime jars / merchant assets it injects) can be resolved.
 * Discovered by the umbrella `com.getlokalapp.paymentsdk.lokal-payment-settings` plugin
 * via ServiceLoader.
 *
 * Contributes unconditionally (settings phase can't see the module dependency graph;
 * see [LokalGatewaySettingsContributor]): an unused Maven repo is a harmless no-op. The
 * real gating is at the project level — nothing Juspay happens unless the host declares
 * the `:juspay` gateway as a dependency (both the iOS `JuspayHostContributor` and the
 * Android `JuspayHostAndroidContributor` self-gate on that).
 *
 * No plugin version is pinned here: Juspay's Android wiring is no longer a host-applied
 * plugin id but a `LokalGatewayHostAndroidContributor` bundled onto the
 * `lokal-payment-android` umbrella's buildscript classpath — same as the iOS host
 * contributors — so there is no plugin id for a host to apply and nothing to version-pin.
 */
class JuspaySettingsContributor : LokalGatewaySettingsContributor {

    override fun contribute(settings: Settings) {
        settings.pluginManagement.repositories.maven { it.setUrl(JUSPAY_MAVEN_URL) }
        settings.dependencyResolutionManagement.repositories.maven { it.setUrl(JUSPAY_MAVEN_URL) }
    }

    private companion object {
        // in.juspay's own Maven repo, needed both to resolve hypersdk.plugin (pulled in
        // transitively by the host-android-contributor, via pluginManagement) and for the
        // runtime jars/merchant assets hypersdk.plugin injects into whichever project
        // applies it (via dependencyResolutionManagement).
        const val JUSPAY_MAVEN_URL = "https://maven.juspay.in/jp-build-packages/hyper-sdk/"
    }
}
