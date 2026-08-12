package com.getlokalapp.paymentsdk.razorpay

import android.app.Activity
import android.os.Bundle
import com.getlokalapp.paymentsdk.infrastructure.SinglePendingHandoff
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject

/**
 * Handoff for the single in-flight checkout. Razorpay's result is delivered to
 * the Activity that called Checkout.open(), so the config + listener can't ride
 * along in the launch Intent (a listener isn't Parcelable) — they're parked
 * here for [RazorpayCheckoutActivity] to pick up. Only one checkout can be in
 * flight at a time, so a single slot is sufficient.
 */
internal val razorpayCheckoutHandoff = SinglePendingHandoff<PendingCheckout>()

internal class PendingCheckout(
    val key: String,
    val data: JSONObject,
    val listener: RazorpayPaymentResultListener?,
)

/**
 * Internal proxy Activity that satisfies Razorpay's requirement that the
 * Activity invoking Checkout.open() implement its result listener. Keeping this
 * interface here means host apps never implement a gateway interface
 * themselves. Runs translucent so only Razorpay's own sheet is visible.
 */
internal class RazorpayCheckoutActivity : Activity(), PaymentResultWithDataListener {

    private var request: PendingCheckout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This proxy handles normal configuration changes itself in the merged
        // manifest, so onCreate is expected once for a live checkout. Process
        // recreation cannot restore the non-Parcelable listener and exits safely.
        val owned = razorpayCheckoutHandoff.take()
        if (owned == null) {
            // No in-flight request — e.g. the process was recreated after death
            // mid-payment and the listener is gone. Nothing to drive; bail.
            finish()
            return
        }
        request = owned

        try {
            Checkout().apply { setKeyID(owned.key) }.open(this, owned.data)
        } catch (t: Throwable) {
            deliverError(GENERIC_ERROR, t.message)
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        deliverOnce { listener ->
            listener.onPaymentSuccess(
                paymentId = razorpayPaymentId.orEmpty(),
                orderId = paymentData?.orderId,
                signature = paymentData?.signature.orEmpty(),
            )
        }
    }

    override fun onPaymentError(code: Int, description: String?, paymentData: PaymentData?) {
        deliverError(code, description)
    }

    private fun deliverError(code: Int, description: String?) {
        deliverOnce { listener -> listener.onPaymentError(code, description) }
    }

    private inline fun deliverOnce(deliver: (RazorpayPaymentResultListener) -> Unit) {
        val owned = request ?: return
        request = null
        owned.listener?.let(deliver)
        finish()
    }

    private companion object {
        // Non-zero so the orchestrator classifies it as a failure, not a cancel
        // (cancel is Razorpay's own code 0).
        const val GENERIC_ERROR = 1
    }
}
