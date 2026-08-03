package com.getlokalapp.paymentsdk.juspay

import kotlinx.serialization.json.JsonObject

/** Absorbs Juspay's event callback so the host never implements a Juspay interface (rulebook #8). */
internal interface JuspayResultListener {
    /**
     * Non-terminal, optional: at most one call per pay(), always before [onResult], meaning
     * HyperSDK's own UI has taken over (maps to [com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.GatewayUi.Presented]).
     * Default no-op since not every platform actual calls it - iOS shows its own internal loader
     * and hides it on this same underlying event without needing to tell the host.
     */
    fun onUiPresented() {}

    /** Terminal: exactly one call per pay(), maps to exactly one PaymentResult. */
    fun onResult(data: JuspayResultData)
}

/**
 * One long-lived instance per [com.getlokalapp.paymentsdk.juspay.JuspayGatewayHandler]
 * (contrast Razorpay's per-pay() client) — HyperSDK is stateful and initiated
 * once, then reused for every process(). No onBackPressed(): confirmed dead
 * code even in matrimony's own shipped implementation, and absent from the
 * iOS API entirely.
 *
 * Neither platform actual takes a host-supplied handle: Android auto-tracks
 * the current Activity (see `:shared`'s hostcontext ActivityTracker), iOS
 * looks up the topmost UIViewController fresh whenever HyperSDK needs one —
 * mirroring matrimony-kmp's confirmed-working design.
 */
internal interface JuspayClient {
    val isInitialised: Boolean

    /** Idempotent; safe to call again on resume. */
    fun initiate(initPayload: JsonObject)

    /** Runs a payment. If not yet initialised, the actual queues/handles the handshake. */
    fun process(processPayload: JsonObject)
    fun setResultListener(listener: JuspayResultListener?)

    /**
     * Clears [listener] only if it is still the active one, so a stale
     * pay() flow's teardown can't remove a newer pay()'s listener.
     */
    fun clearResultListener(listener: JuspayResultListener)
}

/**
 * [tenantId] is iOS-only; Android ignores it (see [com.getlokalapp.paymentsdk.juspay.JuspaySdk.configure]'s
 * kdoc). Neither actual takes a clientId: Android reads it implicitly from the host's
 * Gradle-plugin-injected config, and iOS's actual resolves it itself at runtime from the
 * bundled `MerchantConfig.json` (same file the host-contributor generates for HyperSDK's own
 * asset pipeline) — no host code ever passes one.
 */
internal expect fun createJuspayClient(tenantId: String): JuspayClient
