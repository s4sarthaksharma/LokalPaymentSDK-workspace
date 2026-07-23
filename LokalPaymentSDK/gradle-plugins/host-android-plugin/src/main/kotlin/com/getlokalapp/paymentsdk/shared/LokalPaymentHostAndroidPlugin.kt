package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.LokalGatewayHostAndroidContributor
import com.getlokalapp.paymentsdk.host.transitiveSdkModules
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
 * [LokalPaymentSettingsPlugin] (the settings umbrella). It registers a single
 * `plugins.withId("com.android.application") { … }` **eagerly** during `apply` — that eager
 * registration is what the vendor plugins need, since the withId listener must be in place
 * before the app plugin is applied. It then gates once, in the app module's `afterEvaluate`
 * (where declared dependencies are finally visible): a single [transitiveSdkModules] walk
 * yields which gateways the app ships (directly or transitively via a shared KMP module), and
 * only the contributors whose [LokalGatewayHostAndroidContributor.module] is in that set are
 * invoked — not each contributor re-walking the graph and self-gating.
 *
 * Kept in its own jar (not folded into :gradle-plugins:host-plugin) so the vendor
 * Android plugins the contributors pull in never leak onto the iOS host's classpath.
 */
class LokalPaymentHostAndroidPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val contributors = ServiceLoader.load(
            LokalGatewayHostAndroidContributor::class.java,
            LokalGatewayHostAndroidContributor::class.java.classLoader,
        ).associateBy { it.module }
        if (contributors.isEmpty()) return

        // Register eagerly (the withId listener must be in place before the app plugin is
        // applied), but gate in afterEvaluate where declared deps are visible. One graph walk,
        // targeted at exactly the modules we have contributors for, decides which gateways the
        // app ships (and short-circuits once all are found); call only those, on the app module.
        project.plugins.withId("com.android.application") {
            project.afterEvaluate { app ->
                app.transitiveSdkModules(SDK_GROUP, contributors.keys).forEach { module ->
                    contributors.getValue(module).contribute(app)
                }
            }
        }
    }

    private companion object {
        // The Maven group shared by every Lokal Payment SDK gateway module — the group the
        // transitive dependency walk filters on to find which gateways the app ships.
        const val SDK_GROUP = "com.getlokalapp.paymentsdk"
    }
}
