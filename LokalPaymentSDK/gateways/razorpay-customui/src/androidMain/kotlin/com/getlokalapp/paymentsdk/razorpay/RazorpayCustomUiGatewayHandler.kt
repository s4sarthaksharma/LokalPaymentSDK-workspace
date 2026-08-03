package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.GatewayResultScope
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.TypedPaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.util.Log

/**
 * Singleton handler for [PaymentGateway.RAZORPAY_CUSTOM_UI] — registers itself
 * with [LokalPaymentSdk] in its `init` block, which
 * `RazorpayCustomUiInitializer` (AndroidX App Startup) runs at process start
 * with zero host code.
 * Android-only (see this module's build.gradle.kts for why), so there is no
 * iOS bootstrap: the gateway simply never registers there. No platform
 * handle to grab:
 * [AndroidRazorpayCustomUiClient] reads the current Activity from
 * `:shared`'s hostcontext ActivityTracker at call time (mirrors
 * RazorpayCheckoutGatewayHandler/JuspayGatewayHandler), only ever using it to launch this SDK's own
 * internal proxy Activity ([RazorpayCustomUiActivity]), which owns the
 * WebView Razorpay requires and handles its own onActivityResult — there's
 * nothing for the host to forward.
 *
 * Any method-selection UI (e.g. surfacing installed UPI apps and letting the
 * user pick one) is the host's responsibility — that's app-level UI, not
 * something this SDK owns.
 */
internal object RazorpayCustomUiGatewayHandler : TypedPaymentGatewayHandler<RazorpayCustomUiConfig> {

    private const val TAG = "RazorpayCustomUi"

    override val gateway: PaymentGateway = PaymentGateway.RAZORPAY_CUSTOM_UI

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = VENDOR_SDK_VERSION,
    )

    override val configSerializer = RazorpayCustomUiConfig.serializer()

    init {
        LokalPaymentSdk.register(this)
    }

    /**
     * Runs a payment for the already-decoded [config] and emits exactly one terminal
     * [PaymentResult] (Success / Cancelled / Failure) before completing. LokalPaymentSdk
     * has already parsed the create-order envelope and routed by gateway, so there's no
     * response to re-parse or gateway to re-check here.
     */
    override suspend fun GatewayResultScope.handle(config: RazorpayCustomUiConfig) {
        val client = AndroidRazorpayCustomUiClient()
        client.setPaymentResultListener(object : RazorpayCustomUiResultListener {
            override fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String) {
                Log.d { "[$TAG] payment success, paymentId=$paymentId, orderId=$orderId" }
                sendTerminal(razorpayCustomUiSuccess(paymentId, orderId, signature))
            }

            override fun onPaymentError(code: Int, description: String?) {
                Log.w { "[$TAG] payment error, code=$code, description=$description" }
                sendTerminal(razorpayCustomUiErrorToResult(code, description))
            }
        })
        Log.d { "[$TAG] submitting payment" }
        client.submit(config)

        awaitClose { client.setPaymentResultListener(null) }
    }
}
