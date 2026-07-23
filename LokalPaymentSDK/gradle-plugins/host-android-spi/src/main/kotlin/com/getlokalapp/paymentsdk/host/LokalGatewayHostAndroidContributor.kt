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
 * The umbrella `com.getlokalapp.paymentsdk.lokal-payment-android` plugin discovers
 * every implementation on the host's buildscript classpath with [java.util.ServiceLoader]
 * and calls [contribute] once, eagerly, during its own `apply` — NOT in an
 * `afterEvaluate`. That timing is deliberate: an Android vendor plugin such as
 * `hypersdk.plugin` must be applied *eagerly on the application module* (applying it in
 * `afterEvaluate` is too late, and the KMP-library module the iOS umbrella runs on
 * doesn't expose the `implementation` configuration the vendor plugin injects into).
 * Implementations therefore guard their own work with
 * `target.plugins.withId("com.android.application") { … }` so it only fires on the app
 * module and only once that plugin is applied.
 *
 * Every contributor jar is always on the classpath (the umbrella depends on each), so
 * implementations MUST self-gate — do nothing unless the app actually depends on the
 * owning gateway (directly, or transitively through a shared module) — in the same
 * spirit as the iOS [LokalGatewayHostContributor]s. Because a module's `dependencies { }`
 * block is evaluated after its `plugins { }` block, that check belongs in an
 * `afterEvaluate` inside the `withId` guard, not at apply time. Applying the umbrella is
 * therefore harmless when a gateway isn't used; it wires only the gateways the app ships.
 */
interface LokalGatewayHostAndroidContributor {
    fun contribute(target: Project)
}
