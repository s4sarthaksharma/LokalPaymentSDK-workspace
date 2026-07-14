package com.getlokalapp.paymentsdk

import androidx.startup.Initializer

/**
 * Base for each gateway module's AndroidX App Startup initializer. Centralizes
 * the one thing every gateway shares — a `dependencies()` on
 * [PaymentSdkInitializer], so the ActivityTracker install always runs before
 * any gateway registers — leaving each gateway subclass to implement only
 * `create()`, which returns its SDK singleton. Referencing that singleton runs
 * its `init` block, which registers it with [LokalPaymentSdk]; returning it
 * follows App Startup's contract (create returns the initialized component).
 *
 * Public because gateway modules (separate compilations) subclass it. Each
 * subclass is still declared in its own module's AndroidManifest.xml as a
 * `<meta-data>` keyed by the subclass's class name (no per-module authority).
 */
abstract class GatewayInitializer : Initializer<PaymentGatewayHandler> {

    final override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(PaymentSdkInitializer::class.java)
}
