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
 * the settings buildscript classpath. But where the project-phase umbrella *gates* —
 * calling only the contributor whose module the host imports — this one calls every
 * contributor unconditionally: settings contributions can't be gated on the owning
 * gateway (no dependency graph yet, and the contribution is often load-bearing for the
 * bundled plugin classpath — see [LokalGatewaySettingsContributor]).
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
