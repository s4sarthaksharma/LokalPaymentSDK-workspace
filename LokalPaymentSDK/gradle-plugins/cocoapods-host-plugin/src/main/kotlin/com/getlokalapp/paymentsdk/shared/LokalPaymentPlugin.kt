package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor
import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.ServiceLoader

/**
 * The single host-facing entry point for the Lokal Payment SDK's iOS build glue.
 * A host applies just this plugin and configures one `lokalPaymentSdk { }` block;
 * each SDK gateway contributes its own build-time behavior through a
 * [LokalGatewayHostContributor] discovered via [ServiceLoader], instead of the host
 * applying a separate `*-cocoapods-host` plugin per gateway.
 *
 * This plugin depends on every gateway contributor, so all are present on the
 * buildscript classpath; each self-gates to a no-op unless the host imports its
 * gateway module. Net effect: no gateway behavior unless that module is a declared
 * dependency.
 *
 * It also folds in [SharedCocoapodsPlugin] — the first-party pod plumbing (Maven
 * `iossrc` unpack + Podfile management) — by applying it directly, so the host
 * applies this one plugin for everything. [SharedCocoapodsPlugin] has no standalone
 * plugin id: it's an implementation detail of this umbrella.
 */
class LokalPaymentPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // First-party pod plumbing (native-iap etc.); idempotent to apply.
        project.pluginManager.apply(SharedCocoapodsPlugin::class.java)

        val config = project.extensions.create(
            "lokalPaymentSdk",
            LokalPaymentSdkExtension::class.java,
        )

        // afterEvaluate so contributors see the host's fully-declared dependency set
        // (their import self-gate) and the already-applied cocoapods plugin.
        project.afterEvaluate {
            ServiceLoader.load(
                LokalGatewayHostContributor::class.java,
                LokalGatewayHostContributor::class.java.classLoader,
            ).forEach { it.contribute(project, config) }
        }
    }
}
