package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject

/**
 * Registers itself with [LokalPaymentSdk] to handle
 * [PaymentGateway.RAZORPAY_INTENT] as soon as it's constructed — Android-only
 * (see this module's build.gradle.kts for why). No platform handle to grab:
 * [AndroidRazorpayUpiIntentClient] reads the current Activity from
 * `:shared`'s hostcontext ActivityTracker at call time (mirrors
 * RazorpayCheckoutSdk/JuspaySdk), only ever using it to launch this SDK's own
 * internal proxy Activity ([RazorpayUpiIntentActivity]), which owns the
 * WebView Razorpay requires and handles its own onActivityResult — there's
 * nothing for the host to forward.
 *
 * The host is also responsible for surfacing installed UPI apps and letting
 * the user pick one — that's app-level UI, not something this SDK owns.
 */
class RazorpayUpiIntentSdk : PaymentGatewayHandler {

    override val gateway: PaymentGateway = PaymentGateway.RAZORPAY_INTENT

    init {
        LokalPaymentSdk.register(this)
    }

    /**
     * Runs a payment for the routed `gateway_config` blob and emits exactly one
     * terminal [PaymentResult] (Success / Cancelled / Failure) before completing.
     * LokalPaymentSdk has already parsed the create-order envelope and routed by
     * gateway, so there's no response to re-parse or gateway to re-check here.
     *
     * @param gatewayConfig the opaque `gateway_config` blob for RAZORPAY_INTENT
     */
    override fun pay(gatewayConfig: JsonObject): Flow<PaymentResult> = callbackFlow {
        val config = gatewayConfig.toRazorpayUpiIntentConfig()
        val client = AndroidRazorpayUpiIntentClient()
        client.setPaymentResultListener(object : RazorpayUpiIntentResultListener {
            override fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String) {
                trySend(razorpayUpiIntentSuccess(paymentId, orderId, signature))
                close()
            }

            override fun onPaymentError(code: Int, description: String?) {
                trySend(razorpayUpiIntentErrorToResult(code, description))
                close()
            }
        })
        client.submit(config)

        awaitClose { client.setPaymentResultListener(null) }
    }
}

actual fun createRazorpayUpiIntentHandler(): PaymentGatewayHandler? = RazorpayUpiIntentSdk()
