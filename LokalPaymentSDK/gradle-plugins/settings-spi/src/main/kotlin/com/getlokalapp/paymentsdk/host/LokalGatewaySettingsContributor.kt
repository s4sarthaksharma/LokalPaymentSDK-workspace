package com.getlokalapp.paymentsdk.host

import org.gradle.api.initialization.Settings

/**
 * A gateway's build-time contribution to a host's `settings.gradle.kts`. Each SDK
 * gateway that needs settings-phase setup — e.g. adding its vendor Maven repo, or
 * pinning a default version for a sibling project plugin — ships one implementation
 * and registers it in
 * `META-INF/services/com.getlokalapp.paymentsdk.host.LokalGatewaySettingsContributor`.
 *
 * The umbrella `com.getlokalapp.paymentsdk.lokal-payment-settings` plugin discovers
 * every implementation on the host's settings buildscript classpath with
 * [java.util.ServiceLoader] and calls [contribute] once during settings evaluation.
 * It is the settings-phase twin of `LokalGatewayHostContributor` (the project-phase
 * SPI dispatched by `com.getlokalapp.paymentsdk.lokal-payment`).
 *
 * Unlike the project-phase contributor, implementations do NOT self-gate: the module
 * dependency graph is not yet known during settings evaluation, so a contributor
 * cannot tell whether its gateway is actually in use. Contribute unconditionally —
 * adding an unused Maven repo or pinning the version of a plugin that is never
 * applied are both harmless no-ops. Real gating stays at the project level (whether
 * the host applies the gateway's project plugin / imports its module).
 */
interface LokalGatewaySettingsContributor {
    fun contribute(settings: Settings)
}
