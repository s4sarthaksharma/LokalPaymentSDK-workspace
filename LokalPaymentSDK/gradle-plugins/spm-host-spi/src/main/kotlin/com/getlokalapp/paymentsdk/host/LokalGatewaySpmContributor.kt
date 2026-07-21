package com.getlokalapp.paymentsdk.host

import org.gradle.api.Project

/**
 * A gateway's build-time contribution to the host's generated local Swift package
 * (see `LokalPaymentSpmPlugin`) — the SPM-flavored sibling of
 * [LokalGatewayHostContributor]. Where that one edits the host's generated podspec
 * in place, this one *returns* its contribution instead of performing its own file
 * IO: the umbrella plugin owns the single generated `Package.swift` and must
 * aggregate every gateway's contribution into it, so contributors can't each
 * independently patch the same manifest the way podspec text-editing allows.
 *
 * Each gateway that must link a vendor SPM package into the host's iOS build ships
 * one implementation and registers it in
 * `META-INF/services/com.getlokalapp.paymentsdk.host.LokalGatewaySpmContributor`.
 * Discovered by the umbrella `com.getlokalapp.paymentsdk.lokal-payment-spm` plugin
 * via [java.util.ServiceLoader], exactly like [LokalGatewayHostContributor].
 *
 * Implementations MUST self-gate: return `null` unless the host actually declares
 * the owning gateway module as a dependency — every contributor jar is always on
 * the classpath (the umbrella depends on each), so "this gateway isn't used" is a
 * `null` return, not absence.
 */
interface LokalGatewaySpmContributor {
    fun contribute(target: Project, config: LokalPaymentSdkSpmExtension): SpmContribution?
}
