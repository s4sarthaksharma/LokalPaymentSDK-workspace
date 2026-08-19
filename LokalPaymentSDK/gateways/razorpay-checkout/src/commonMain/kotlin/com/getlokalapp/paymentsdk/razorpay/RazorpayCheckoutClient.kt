package com.getlokalapp.paymentsdk.razorpay

internal interface RazorpayCheckoutClient {
    fun openCheckout(config: RazorpayCheckoutConfig)
    fun setPaymentResultListener(listener: RazorpayPaymentResultListener?)
}

/**
 * Raw callback forwarded as-is from the platform Razorpay SDK.
 * Classifying `code` into cancellation vs. a real failure happens one
 * layer up, in orchestration — not here — mirroring how matrimony keeps
 * that judgment out of the platform client itself.
 */
internal interface RazorpayPaymentResultListener {
    fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String)
    fun onPaymentError(code: Int, description: String?)

    /**
     * Not a Razorpay callback: reported by the platform client itself when the UI it handed the
     * payment to was destroyed before Razorpay could call either of the above, which makes a real
     * result impossible (Razorpay delivers only to the exact Activity/ViewController that opened
     * checkout). Exists as its own callback rather than an `onPaymentError` code so the
     * classification stays in orchestration with the rest - see [razorpayUiDestroyed].
     */
    fun onCheckoutUiDestroyed()
}

/**
 * Lets common orchestration (RazorpayCheckoutGatewayHandler.pay) obtain the platform's own
 * Razorpay client without the host having to reach into the razorpay package
 * or know which actual (Android/iOS) backs it.
 */
internal expect fun createRazorpayCheckoutClient(): RazorpayCheckoutClient
