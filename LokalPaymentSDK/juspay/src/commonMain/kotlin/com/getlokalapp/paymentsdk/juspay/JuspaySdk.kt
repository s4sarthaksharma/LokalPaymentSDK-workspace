package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject

/**
 * Registers itself with [LokalPaymentSdk] and calls [initiate] with
 * [initPayload], both as soon as it's constructed — no platform handle
 * needed from the host: Android auto-tracks the current Activity (see
 * `:shared`'s hostcontext ActivityTracker), iOS looks up the topmost
 * UIViewController fresh whenever HyperSDK needs one. Holds one long-lived
 * [JuspayClient] for the lifetime of this instance — HyperSDK is initiated
 * once and reused for
 * every [pay] call, not recreated per payment (mirrors matrimony-kmp's
 * confirmed-working design).
 *
 * [clientId]/[tenantId] are iOS-only — Android reads its clientId implicitly
 * from the host's Gradle-plugin-injected config. The defaults borrow
 * matrimony's real, already-registered values; a real host must swap in its
 * own before shipping. No onBackPressed forwarding on either platform —
 * confirmed dead code even in matrimony's own shipped implementation.
 */
class JuspaySdk(
    initPayload: JsonObject,
    clientId: String = "lokalmatrimony",
    tenantId: String = "juspayindia",
) : PaymentGatewayHandler {

    override val gateway: PaymentGateway = PaymentGateway.JUSPAY

    private val client: JuspayClient = createJuspayClient(clientId, tenantId)

    init {
        LokalPaymentSdk.register(this)
        client.initiate(initPayload)
    }

    /** Call again — e.g. on resume — to refresh HyperSDK's cached init payload. */
    fun initiate(initPayload: JsonObject) = client.initiate(initPayload)

    override fun pay(gatewayConfig: JsonObject): Flow<PaymentResult> = callbackFlow {
        val config = gatewayConfig.toJuspayConfig()
        client.setResultListener(object : JuspayResultListener {
            override fun onResult(data: JuspayResultData) {
                trySend(juspayResultToPaymentResult(data))
                close()
            }
        })
        client.process(config.sdkPayload)
        awaitClose { client.setResultListener(null) }
    }

    override fun dispose() {
        client.dispose()
        super.dispose()
    }
}
