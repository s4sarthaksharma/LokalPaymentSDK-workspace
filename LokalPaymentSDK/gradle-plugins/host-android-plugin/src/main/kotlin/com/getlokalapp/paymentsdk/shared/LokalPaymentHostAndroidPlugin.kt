package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.LokalGatewayHostAndroidContributor
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.ServiceLoader

/**
 * The single host-facing entry point for the Lokal Payment SDK's **Android** app-module
 * setup (`com.getlokalapp.paymentsdk.lokal-payment-android`). A host applies just
 * this plugin on its `com.android.application` module and every gateway that needs
 * Android build wiring (Juspay applying `hypersdk.plugin` + forwarding its merchant
 * client id, …) is configured automatically; each SDK gateway contributes through a
 * [LokalGatewayHostAndroidContributor] discovered via [ServiceLoader], instead of the
 * host applying a separate vendor plugin id per gateway.
 *
 * The project-phase Android twin of [LokalPaymentPlugin] (the iOS umbrella) and
 * [LokalPaymentSettingsPlugin] (the settings umbrella). Unlike the iOS umbrella, this
 * dispatches **eagerly** — not in an `afterEvaluate` — because an Android vendor plugin
 * must be applied eagerly on the application module. Each contributor guards its own
 * work with `target.plugins.withId("com.android.application") { … }` (see
 * [LokalGatewayHostAndroidContributor]), so the eager dispatch is harmless on a
 * non-application module.
 *
 * Kept in its own jar (not folded into :gradle-plugins:host-plugin) so the vendor
 * Android plugins the contributors pull in never leak onto the iOS host's classpath.
 */
class LokalPaymentHostAndroidPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        ServiceLoader.load(
            LokalGatewayHostAndroidContributor::class.java,
            LokalGatewayHostAndroidContributor::class.java.classLoader,
        ).forEach { it.contribute(project) }
    }
}
