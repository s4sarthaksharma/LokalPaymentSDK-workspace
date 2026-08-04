package com.getlokalapp.paymentsdk.host

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency

/**
 * A gateway's build-time contributions to the host's generated local Swift package
 * (see `LokalPaymentPlugin`) — the SPM-flavored sibling of
 * [LokalGatewayHostContributor]. Where that one edits the host's generated podspec
 * in place, this one *returns* its contributions instead of performing its own file
 * IO: the umbrella plugin owns the single generated `Package.swift` and must
 * aggregate every gateway's contributions into it, so contributors can't each
 * independently patch the same manifest the way podspec text-editing allows.
 *
 * Each gateway that must link a vendor SPM package into the host's iOS build ships
 * one implementation and registers it in
 * `META-INF/services/com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor`.
 * Discovered by the umbrella `com.getlokalapp.paymentsdk.lokal-payment` plugin
 * via [java.util.ServiceLoader], exactly like [LokalGatewayHostContributor].
 *
 * Gating is the umbrella plugin's job, not the contributor's: it scans the host's
 * declared dependencies once (all under group `com.getlokalapp.paymentsdk`) and calls
 * [contribute] only for the contributor whose [module] the host actually imports,
 * passing that resolved [Dependency]. Every contributor jar is always on the classpath
 * (the umbrella depends on each), so a gateway the host doesn't import is simply never
 * called — no per-contributor self-gate scan.
 */
interface LokalGatewayHostContributor {
    /** The SDK module (group `com.getlokalapp.paymentsdk`) this gateway owns, e.g. `"juspay"`. */
    val module: String

    /**
     * The [HostContribution]s this gateway needs from the host's iOS build — any combination
     * of kinds, in any number. Returns an empty list to contribute nothing, which covers the
     * present-but-inapplicable case (e.g. a required artifact isn't published); there is
     * deliberately no second, nullable way to say the same thing.
     */
    fun contribute(
        target: Project,
        config: LokalPaymentSdkExtension,
        dependency: Dependency,
    ): List<HostContribution>
}
