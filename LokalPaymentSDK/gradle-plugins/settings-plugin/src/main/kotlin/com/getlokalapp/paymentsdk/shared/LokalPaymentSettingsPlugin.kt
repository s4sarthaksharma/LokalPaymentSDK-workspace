package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.LokalGatewaySettingsContributor
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import java.util.ServiceLoader

/**
 * The single host-facing entry point for the Lokal Payment SDK's `settings.gradle.kts`
 * setup. A host applies just this plugin's id and every gateway that needs
 * settings-phase wiring (Juspay's private Maven repo + its host-plugin version pin,
 * …) is configured automatically; each SDK gateway contributes through a
 * [LokalGatewaySettingsContributor] discovered via [ServiceLoader], instead of the
 * host applying a separate `*-host-settings` plugin per gateway.
 *
 * Settings-phase twin of [LokalPaymentPlugin] (the project-phase umbrella). Like it,
 * this plugin depends on every gateway settings contributor, so all are present on
 * the settings buildscript classpath. Contributors do not self-gate (see
 * [LokalGatewaySettingsContributor]); their work is harmless when the owning gateway
 * is unused.
 *
 * This and all contributor jars deliberately resolve from generic repos only (no
 * vendor repos): a contributor's whole job may be to ADD a vendor repo, so it must
 * not itself require that repo to resolve.
 */
class LokalPaymentSettingsPlugin : Plugin<Settings> {

    override fun apply(settings: Settings) {
        ServiceLoader.load(
            LokalGatewaySettingsContributor::class.java,
            LokalGatewaySettingsContributor::class.java.classLoader,
        ).forEach { it.contribute(settings) }
    }
}
