package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.PaymentPresenter
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.model.parseCreateOrderResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Registers itself with [LokalPaymentSdk] to handle
 * [PaymentGateway.RAZORPAY_CHECKOUT] as soon as it's constructed — nothing
 * else to wire up. [presenter] is fixed at construction, not passed per
 * [pay] call — one instance per checkout surface. Call [dispose] when
 * [presenter]'s underlying Activity/UIViewController goes away, so the
 * registry doesn't hold a stale reference.
 */
class RazorpayCheckoutSdk(private val presenter: PaymentPresenter) : PaymentGatewayHandler {

    override val gateway: PaymentGateway = PaymentGateway.RAZORPAY_CHECKOUT

    init {
        LokalPaymentSdk.register(this)
    }

    override fun pay(orderResponseJson: String): Flow<PaymentResult> = callbackFlow {
        val response = parseCreateOrderResponse(orderResponseJson)
        val responseGateway = PaymentGateway.fromValue(response.gateway)

        if (responseGateway != PaymentGateway.RAZORPAY_CHECKOUT) {
            trySend(
                PaymentResult.Failure(
                    PaymentError(
                        code = "unsupported_gateway",
                        message = "Unsupported gateway ${responseGateway ?: response.gateway}; " +
                            "RazorpayCheckoutSdk only handles RAZORPAY_CHECKOUT.",
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
}
