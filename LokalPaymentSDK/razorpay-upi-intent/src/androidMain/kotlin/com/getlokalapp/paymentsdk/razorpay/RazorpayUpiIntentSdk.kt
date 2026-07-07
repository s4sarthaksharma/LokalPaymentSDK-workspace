package com.getlokalapp.paymentsdk.razorpay

import android.app.Activity
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.model.parseCreateOrderResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Registers itself with [LokalPaymentSdk] to handle
 * [PaymentGateway.RAZORPAY_INTENT] as soon as it's constructed — Android-only
 * (see this module's build.gradle.kts for why). Unlike RazorpayCheckoutSdk,
 * the host supplies its own [Activity] directly rather than a
 * [com.getlokalapp.paymentsdk.PaymentPresenter]: this module has no iOS
 * counterpart, so the multiplatform presenter abstraction doesn't buy
 * anything here. [activity] is only ever used to launch this SDK's own
 * internal proxy Activity ([RazorpayUpiIntentActivity]), which owns the
 * WebView Razorpay requires and handles its own onActivityResult — there's
 * nothing for the host to forward. Call [dispose] when [activity] goes away.
 *
 * The host is also responsible for surfacing installed UPI apps and letting
 * the user pick one — that's app-level UI, not something this SDK owns.
 */
class RazorpayUpiIntentSdk(private val activity: Activity) : PaymentGatewayHandler {

    override val gateway: PaymentGateway = PaymentGateway.RAZORPAY_INTENT

    init {
        LokalPaymentSdk.register(this)
    }

    /**
     * Runs a payment for the given create-order response and emits exactly one
     * terminal [PaymentResult] (Success / Cancelled / Failure) before completing.
     *
     * @param orderResponseJson the raw create-order response body from the host's backend
     */
    override fun pay(orderResponseJson: String): Flow<PaymentResult> = callbackFlow {
        val response = parseCreateOrderResponse(orderResponseJson)
        val responseGateway = PaymentGateway.fromValue(response.gateway)

        if (responseGateway != PaymentGateway.RAZORPAY_INTENT) {
            trySend(
                PaymentResult.Failure(
                    PaymentError(
                        code = "unsupported_gateway",
                        message = "Unsupported gateway ${responseGateway ?: response.gateway}; " +
                            "RazorpayUpiIntentSdk only handles RAZORPAY_INTENT.",
                    ),
                ),
            )
            close()
            return@callbackFlow
        }

        val config = response.toRazorpayUpiIntentConfig()
        val client = AndroidRazorpayUpiIntentClient(activity)
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
