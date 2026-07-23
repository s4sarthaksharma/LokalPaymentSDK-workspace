package com.getlokalapp.paymentsdk.host

import org.gradle.api.Project

/**
 * A gateway's build-time contribution to a host's **Android** application module —
 * the Android sibling of [LokalGatewayHostContributor] (iOS) and
 * [LokalGatewaySettingsContributor] (settings phase). Each SDK gateway that needs to
 * wire something into the host's `com.android.application` build (e.g. Juspay applying
 * `hypersdk.plugin` and forwarding the merchant client id) ships one implementation
 * and registers it in
 * `META-INF/services/com.getlokalapp.paymentsdk.host.LokalGatewayHostAndroidContributor`.
 *
 * Gating is the umbrella plugin's job, not the contributor's. The umbrella
 * `com.getlokalapp.paymentsdk.lokal-payment-android` plugin discovers every implementation
 * with [java.util.ServiceLoader], keyed by the [module] each owns. It registers a single
 * `plugins.withId("com.android.application") { … }` eagerly during its own `apply` (that
 * eager registration is what the vendor plugins need — the withId listener must be in place
 * before the app plugin is applied). Then, in the app module's `afterEvaluate` (where
 * declared dependencies are finally visible), it computes the set of SDK gateways the app
 * ships — directly or transitively through a shared KMP module — with **one** graph walk
 * (see `transitiveSdkModules`), and calls [contribute] only for the contributors whose
 * [module] is in that set.
 *
 * So [contribute] is invoked already on the `com.android.application` module, inside its
 * `afterEvaluate`, and only when the app actually ships the owning gateway — no per-contributor
 * `withId` wrapper, `afterEvaluate`, or self-gate graph walk (every contributor jar is always
 * on the classpath, so "not used" is "never called", not absence). Applying the umbrella is
 * harmless when a gateway isn't used; it wires only the gateways the app ships.
 */
interface LokalGatewayHostAndroidContributor {
    /** The SDK module (group `com.getlokalapp.paymentsdk`) this gateway owns, e.g. `"juspay"`. */
    val module: String

    fun contribute(target: Project)
}
