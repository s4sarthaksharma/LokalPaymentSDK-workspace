package com.getlokalapp.paymentsdk.razorpay

import android.app.Activity
import android.content.Intent

/**
 * Drives Razorpay's UPI Intent flow on Android by launching
 * [RazorpayUpiIntentActivity], an internal proxy that owns the WebView
 * Razorpay requires and implements its result listener — mirrors
 * `:razorpay-checkout`'s AndroidRazorpayCheckoutClient for hosted Checkout.
 */
internal class AndroidRazorpayUpiIntentClient(private val activity: Activity) {

    private var listener: RazorpayUpiIntentResultListener? = null

    fun submit(config: RazorpayUpiIntentConfig) {
        RazorpayUpiIntentBridge.pending = PendingUpiIntentCheckout(
            key = config.razorpayKey,
            data = config.data.toOrgJson(),
            listener = listener,
        )
        activity.startActivity(Intent(activity, RazorpayUpiIntentActivity::class.java))
    }

    fun setPaymentResultListener(listener: RazorpayUpiIntentResultListener?) {
        this.listener = listener
    }
}
