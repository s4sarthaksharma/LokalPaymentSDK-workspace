package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.PaymentPresenter

interface RazorpayCheckoutClient {
    fun openCheckout(config: RazorpayCheckoutConfig, presenter: PaymentPresenter)
    fun setPaymentResultListener(listener: RazorpayPaymentResultListener?)
}
