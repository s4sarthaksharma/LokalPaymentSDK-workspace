package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.parseGatewayConfigOrFail
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import com.getlokalapp.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject

/**
 * Singleton handler for [PaymentGateway.RAZORPAY_CHECKOUT] — registers
 * itself with [LokalPaymentSdk] in its `init` block, which runs at app
 * startup with zero host code: `RazorpayCheckoutInitializer` (AndroidX App
 * Startup) touches this object on Android, the `@EagerInitialization` hook in
 * `RazorpayCheckoutEagerInit.kt` does on iOS. No platform handle to grab:
 * Android auto-tracks the current Activity and iOS looks up the topmost
 * UIViewController fresh, both via `:shared`'s hostcontext utilities
 * (mirrors JuspaySdk). Each [pay] call builds its own short-lived platform
 * client.
 */
internal object RazorpayCheckoutSdk : PaymentGatewayHandler {

    private const val TAG = "RazorpayCheckout"

    override val gateway: PaymentGateway = PaymentGateway.RAZORPAY_CHECKOUT

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = VENDOR_SDK_VERSION,
    )

    init {
        LokalPaymentSdk.register(this)
    }

    override fun pay(gatewayConfig: JsonObject): Flow<PaymentGatewayEvent> = callbackFlow {
        val config = parseGatewayConfigOrFail { gatewayConfig.toRazorpayCheckoutConfig() } ?: return@callbackFlow
        val client = createRazorpayCheckoutClient()
        client.setPaymentResultListener(object : RazorpayPaymentResultListener {
            override fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String) {
                Log.d { "[$TAG] payment success, paymentId=$paymentId, orderId=$orderId" }
                trySend(razorpaySuccess(paymentId, orderId, signature))
                close()
            }

            override fun onPaymentError(code: Int, description: String?) {
                Log.w { "[$TAG] payment error, code=$code, description=$description" }
                trySend(razorpayErrorToResult(code, description))
                close()
            }
        })
        Log.d { "[$TAG] opening checkout" }
        client.openCheckout(config)

        awaitClose { client.setPaymentResultListener(null) }
    }
}
