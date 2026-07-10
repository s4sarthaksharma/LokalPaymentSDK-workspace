package com.getlokalapp.paymentsdk.juspay

import kotlinx.serialization.json.JsonObject

/** Absorbs Juspay's event callback so the host never implements a Juspay interface (rulebook #8). */
internal interface JuspayResultListener {
    /** Terminal: exactly one call per pay(), maps to exactly one PaymentResult. */
    fun onResult(data: JuspayResultData)
}

/**
 * One long-lived instance per [com.getlokalapp.paymentsdk.juspay.JuspaySdk]
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
}

/** [clientId]/[tenantId] are iOS-only; Android ignores them (see [com.getlokalapp.paymentsdk.juspay.JuspaySdk]'s kdoc). */
internal expect fun createJuspayClient(clientId: String, tenantId: String): JuspayClient
