package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.PaymentPresenter
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener

class AndroidRazorpayCheckoutClient : RazorpayCheckoutClient, PaymentResultWithDataListener {

    private var listener: RazorpayPaymentResultListener? = null

    override fun openCheckout(config: RazorpayCheckoutConfig, presenter: PaymentPresenter) {
        Checkout().apply { setKeyID(config.razorpayKey) }
            .open(presenter.activity, config.data.toOrgJson())
    }

    override fun setPaymentResultListener(listener: RazorpayPaymentResultListener?) {
        this.listener = listener
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        listener?.onPaymentSuccess(
            paymentId = razorpayPaymentId.orEmpty(),
            orderId = paymentData?.orderId,
            signature = paymentData?.signature.orEmpty(),
        )
    }

    override fun onPaymentError(code: Int, description: String?, paymentData: PaymentData?) {
        listener?.onPaymentError(code, description)
    }
}
