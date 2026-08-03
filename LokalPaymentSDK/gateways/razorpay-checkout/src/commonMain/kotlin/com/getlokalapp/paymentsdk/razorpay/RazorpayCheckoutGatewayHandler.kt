package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.GatewayResultScope
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.TypedPaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.util.Log

/**
 * Singleton handler for [PaymentGateway.RAZORPAY_CHECKOUT] — registers
 * itself with [LokalPaymentSdk] in its `init` block, which runs at app
 * startup with zero host code: `RazorpayCheckoutInitializer` (AndroidX App
 * Startup) touches this object on Android, the `@EagerInitialization` hook in
 * `RazorpayCheckoutEagerInit.kt` does on iOS. No platform handle to grab:
 * Android auto-tracks the current Activity and iOS looks up the topmost
 * UIViewController fresh, both via `:shared`'s hostcontext utilities
 * (mirrors JuspayGatewayHandler). Each [pay] call builds its own short-lived platform
 * client.
 */
internal object RazorpayCheckoutGatewayHandler : TypedPaymentGatewayHandler<RazorpayCheckoutConfig> {

    private const val TAG = "RazorpayCheckout"

    override val gateway: PaymentGateway = PaymentGateway.RAZORPAY_CHECKOUT

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = VENDOR_SDK_VERSION,
    )

    override val configSerializer = RazorpayCheckoutConfig.serializer()

    init {
        LokalPaymentSdk.register(this)
    }

    override suspend fun GatewayResultScope.handle(config: RazorpayCheckoutConfig) {
        val client = createRazorpayCheckoutClient()
        client.setPaymentResultListener(object : RazorpayPaymentResultListener {
            override fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String) {
                Log.d { "[$TAG] payment success, paymentId=$paymentId, orderId=$orderId" }
                sendTerminal(razorpaySuccess(paymentId, orderId, signature))
            }

            override fun onPaymentError(code: Int, description: String?) {
                Log.w { "[$TAG] payment error, code=$code, description=$description" }
                sendTerminal(razorpayErrorToResult(code, description))
            }
        })
        Log.d { "[$TAG] opening checkout" }
        client.openCheckout(config)

        awaitClose { client.setPaymentResultListener(null) }
    }
}
