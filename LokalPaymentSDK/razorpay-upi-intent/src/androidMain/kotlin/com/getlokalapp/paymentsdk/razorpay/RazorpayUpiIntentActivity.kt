package com.getlokalapp.paymentsdk.razorpay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.razorpay.Razorpay
import org.json.JSONObject

/**
 * Handoff for the single in-flight UPI Intent payment. Same reasoning as
 * `:razorpay-checkout`'s RazorpayCheckoutBridge — the listener isn't
 * Parcelable, so it can't ride along in the launch Intent; it's parked here
 * for [RazorpayUpiIntentActivity] to pick up. Only one UPI Intent payment
 * can be in flight at a time, so a single slot is sufficient.
 */
internal object RazorpayUpiIntentBridge {
    @Volatile
    var pending: PendingUpiIntentCheckout? = null
}

internal class PendingUpiIntentCheckout(
    val key: String,
    val data: JSONObject,
    val listener: RazorpayUpiIntentResultListener?,
)

/**
 * Internal proxy Activity that owns the WebView Razorpay's UPI Intent flow
 * requires as a JS bridge (never shown to the user) and satisfies Razorpay's
 * requirement that the Activity calling submit() implement its result
 * listener — mirrors RazorpayCheckoutActivity's role for hosted Checkout.
 * Keeping this here means the host never supplies a WebView, never forwards
 * onActivityResult, and only ever passes an Activity to launch this one.
 * Runs translucent — nothing of this Activity's own UI is shown; if the
 * flow routes to an external UPI app, only that app's own screen appears.
 */
internal class RazorpayUpiIntentActivity : Activity(), PaymentResultWithDataListener {

    private var razorpay: Razorpay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pending = RazorpayUpiIntentBridge.pending
        if (pending == null) {
            // No in-flight request — e.g. the process was recreated after death
            // mid-payment and the listener is gone. Nothing to drive; bail.
            finish()
            return
        }

        // Launch once; on configuration-change recreation submit() is already running.
        if (savedInstanceState == null) {
            try {
                val webView = WebView(this)
                razorpay = Razorpay(this, pending.key).apply { setWebView(webView) }
                    .also { it.submit(pending.data, this) }
            } catch (t: Throwable) {
                deliverError(GENERIC_ERROR, t.message)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Some UPI apps return control via an Android Intent result — this
        // Activity is the one that launched them, so it's the one Android
        // delivers the result to. Nothing for the host to do here.
        razorpay?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        takeListener()?.onPaymentSuccess(
            paymentId = razorpayPaymentId.orEmpty(),
            orderId = paymentData?.orderId,
            signature = paymentData?.signature.orEmpty(),
        )
        finish()
    }

    override fun onPaymentError(code: Int, description: String?, paymentData: PaymentData?) {
        deliverError(code, description)
    }

    private fun deliverError(code: Int, description: String?) {
        takeListener()?.onPaymentError(code, description)
        finish()
    }

    /** Returns the pending listener once and clears the slot so it fires exactly once. */
    private fun takeListener(): RazorpayUpiIntentResultListener? {
        val listener = RazorpayUpiIntentBridge.pending?.listener
        RazorpayUpiIntentBridge.pending = null
        return listener
    }

    private companion object {
        // Non-zero so the orchestrator classifies it as a failure, not a cancel.
        const val GENERIC_ERROR = 1
    }
}
