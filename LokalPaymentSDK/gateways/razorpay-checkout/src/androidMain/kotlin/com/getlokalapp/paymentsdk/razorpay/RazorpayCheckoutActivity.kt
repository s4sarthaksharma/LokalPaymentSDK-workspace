package com.getlokalapp.paymentsdk.razorpay

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.getlokalapp.paymentsdk.infrastructure.OperationProxy
import com.getlokalapp.paymentsdk.infrastructure.SinglePendingOperation
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject

/**
 * The single in-flight checkout: its launch payload for [RazorpayCheckoutActivity] to pick up (a
 * listener isn't Parcelable, so none of this can ride along in the launch Intent), and the listener
 * waiting for the result until the checkout is settled. See [SinglePendingOperation] for why those
 * two lifetimes are tracked separately.
 */
internal val razorpayCheckoutOperation = SinglePendingOperation<CheckoutLaunch, RazorpayPaymentResultListener>()

/** What the proxy needs to open Razorpay's sheet, and nothing that outlives opening it. */
internal class CheckoutLaunch(
    val key: String,
    val data: JSONObject,
)

/**
 * Internal proxy Activity that satisfies Razorpay's requirement that the
 * Activity invoking Checkout.open() implement its result listener. Keeping this
 * interface here means host apps never implement a gateway interface
 * themselves. Runs translucent so only Razorpay's own sheet is visible.
 */
internal class RazorpayCheckoutActivity : ComponentActivity(), PaymentResultWithDataListener {

    /**
     * Owns when this checkout is settled, including the case Razorpay itself cannot report: the system
     * destroying this proxy mid-checkout while the process lives on — backgrounded during a UPI
     * hand-off and reclaimed, or "don't keep activities".
     */
    private val proxy = OperationProxy(this, razorpayCheckoutOperation, TAG) { listener ->
        listener.onCheckoutUiDestroyed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launch = proxy.takeLaunchOrSettle() ?: return

        try {
            Checkout().apply { setKeyID(launch.key) }.open(this, launch.data)
        } catch (t: Throwable) {
            deliverError(GENERIC_ERROR, t.message)
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        proxy.deliverTerminal { listener ->
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
        proxy.deliverTerminal { listener -> listener.onPaymentError(code, description) }
    }

    private companion object {
        const val TAG = "RazorpayCheckout"

        // Non-zero so the orchestrator classifies it as a failure, not a cancel
        // (cancel is Razorpay's own code 0).
        const val GENERIC_ERROR = 1
    }
}
