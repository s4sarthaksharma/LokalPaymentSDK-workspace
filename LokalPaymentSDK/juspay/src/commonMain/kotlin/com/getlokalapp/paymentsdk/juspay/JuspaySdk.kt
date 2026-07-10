package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject

/**
 * Singleton handler for [PaymentGateway.JUSPAY]. Unlike the Razorpay
 * gateways, this one can't self-register at process start — HyperSDK needs a
 * host-supplied init payload — so the host's one [initialize] call is the
 * trigger: call it once at app startup (from `commonMain` is fine, same call
 * on both platforms). No platform handle needed from the host: Android
 * auto-tracks the current Activity (see `:shared`'s hostcontext
 * ActivityTracker), iOS looks up the topmost UIViewController fresh whenever
 * HyperSDK needs one.
 *
 * Holds one long-lived [JuspayClient] for the app's lifetime — HyperSDK is
 * initiated once and reused for every [pay] call, not recreated per payment,
 * and never terminated (mirrors matrimony-kmp's confirmed-working design).
 */
object JuspaySdk : PaymentGatewayHandler {

    override val gateway: PaymentGateway = PaymentGateway.JUSPAY

    private var client: JuspayClient? = null

    /**
     * Registers this handler with [LokalPaymentSdk] and initiates HyperSDK
     * with [initPayload]. Safe to call again — e.g. on resume, or when the
     * payload changes — to refresh HyperSDK's cached init payload; the
     * underlying client is created once, so [clientId]/[tenantId] are
     * honored from the first call only.
     *
     * [clientId]/[tenantId] are iOS-only — Android reads its clientId
     * implicitly from the host's Gradle-plugin-injected config. The defaults
     * borrow matrimony's real, already-registered values; a real host must
     * swap in its own before shipping. No onBackPressed forwarding on either
     * platform — confirmed dead code even in matrimony's own shipped
     * implementation.
     */
    fun initialize(
        initPayload: JsonObject,
        clientId: String = "lokalmatrimony",
        tenantId: String = "juspayindia",
    ) {
        val c = client ?: createJuspayClient(clientId, tenantId).also { client = it }
        LokalPaymentSdk.register(this)
        c.initiate(initPayload)
    }

    override fun pay(gatewayConfig: JsonObject): Flow<PaymentResult> {
        // Unreachable via LokalPaymentSdk.pay() (routing requires the
        // registration initialize() performs), but pay() is public on this
        // object — fail gracefully rather than crash if called directly.
        val c = client ?: return flowOf(
            PaymentResult.Failure(
                PaymentError(
                    code = "juspay_not_initialized",
                    message = "JuspaySdk.initialize() was never called.",
                ),
            ),
        )
        return callbackFlow {
            val config = gatewayConfig.toJuspayConfig()
            c.setResultListener(object : JuspayResultListener {
                override fun onResult(data: JuspayResultData) {
                    trySend(juspayResultToPaymentResult(data))
                    close()
                }
            })
            c.process(config.sdkPayload)
            awaitClose { c.setResultListener(null) }
        }
    }
}
