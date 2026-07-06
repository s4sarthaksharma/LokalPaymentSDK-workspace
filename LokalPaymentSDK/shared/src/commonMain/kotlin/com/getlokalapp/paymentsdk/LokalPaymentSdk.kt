package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.model.parseCreateOrderResponse
import com.getlokalapp.paymentsdk.razorpay.RazorpayPaymentResultListener
import com.getlokalapp.paymentsdk.razorpay.createRazorpayCheckoutClient
import com.getlokalapp.paymentsdk.razorpay.razorpayErrorToResult
import com.getlokalapp.paymentsdk.razorpay.razorpaySuccess
import com.getlokalapp.paymentsdk.razorpay.toRazorpayCheckoutConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Entry point for the shared Lokal Payment SDK.
 *
 * The host calls its own backend to create an order, then hands the raw
 * create-order response to [pay]. The SDK owns everything from there:
 * gateway routing, opening the platform checkout sheet, and normalizing the
 * gateway's raw callback into a single terminal [PaymentResult]. The host
 * never touches the razorpay package.
 */
class LokalPaymentSdk {

    /**
     * Runs a payment for the given create-order response and emits exactly one
     * terminal [PaymentResult] (Success / Cancelled / Failure) before completing.
     *
     * @param orderResponseJson the raw create-order response body from the host's backend
     * @param presenter the platform UI context the checkout sheet presents itself on
     */
    fun pay(orderResponseJson: String, presenter: PaymentPresenter): Flow<PaymentResult> = callbackFlow {
        val response = parseCreateOrderResponse(orderResponseJson)
        val gateway = PaymentGateway.fromValue(response.gateway)

        if (gateway != PaymentGateway.RAZORPAY_CHECKOUT) {
            trySend(
                PaymentResult.Failure(
                    PaymentError(
                        code = "unsupported_gateway",
                        message = "Unsupported gateway ${gateway ?: response.gateway}; " +
                            "v1 only supports Razorpay Checkout.",
                    ),
                ),
            )
            close()
            return@callbackFlow
        }

        val config = response.toRazorpayCheckoutConfig()
        val client = createRazorpayCheckoutClient()
        client.setPaymentResultListener(object : RazorpayPaymentResultListener {
            override fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String) {
                trySend(razorpaySuccess(paymentId, orderId, signature))
                close()
            }

            override fun onPaymentError(code: Int, description: String?) {
                trySend(razorpayErrorToResult(code, description))
                close()
            }
        })
        client.openCheckout(config, presenter)

        awaitClose { client.setPaymentResultListener(null) }
    }

    companion object {
        const val VERSION: String = "0.0.1"
    }
}
