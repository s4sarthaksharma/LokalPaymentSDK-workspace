package com.getlokalapp.paymentsdk.host

import org.gradle.api.Project

/**
 * A gateway's build-time contribution to an iOS host build. Each SDK gateway that
 * must touch the host build — e.g. inject its vendor trunk pod into the host's
 * generated podspec — ships one implementation and registers it in
 * `META-INF/services/com.getlokalapp.paymentsdk.host.LokalGatewayHostContributor`.
 *
 * The umbrella `com.getlokalapp.paymentsdk.lokal-payment` plugin discovers every
 * implementation on the host's buildscript classpath with [java.util.ServiceLoader]
 * and calls [contribute] once, in the host's `afterEvaluate`.
 *
 * Implementations MUST self-gate: do nothing unless the host actually declares the
 * owning gateway module as a dependency. Every contributor jar is always on the
 * classpath (the umbrella depends on each), so "this gateway isn't used" is an
 * early return, not absence — no gateway behavior happens unless its module is
 * imported.
 */
interface LokalGatewayHostContributor {
    fun contribute(target: Project, config: LokalPaymentSdkExtension)
}
