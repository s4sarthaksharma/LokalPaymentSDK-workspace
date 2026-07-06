package com.getlokalapp.paymentsdk.razorpay

import android.content.Intent
import com.getlokalapp.paymentsdk.PaymentPresenter

/**
 * Drives Razorpay Checkout on Android by launching [RazorpayCheckoutActivity],
 * an internal proxy that implements Razorpay's result listener. Razorpay
 * requires the Activity that calls Checkout.open() to implement that interface,
 * so the SDK owns that Activity rather than pushing the requirement onto the
 * host — the host just supplies any Activity via the PaymentPresenter.
 */
class AndroidRazorpayCheckoutClient : RazorpayCheckoutClient {

    private var listener: RazorpayPaymentResultListener? = null

    override fun openCheckout(config: RazorpayCheckoutConfig, presenter: PaymentPresenter) {
        RazorpayCheckoutBridge.pending = PendingCheckout(
            key = config.razorpayKey,
            data = config.data.toOrgJson(),
            listener = listener,
        )
        presenter.activity.startActivity(
            Intent(presenter.activity, RazorpayCheckoutActivity::class.java),
        )
    }

    override fun setPaymentResultListener(listener: RazorpayPaymentResultListener?) {
        this.listener = listener
    }
}
