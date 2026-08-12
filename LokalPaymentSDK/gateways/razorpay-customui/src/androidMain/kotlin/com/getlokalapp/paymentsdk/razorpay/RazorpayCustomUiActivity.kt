package com.getlokalapp.paymentsdk.razorpay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker
import com.getlokalapp.paymentsdk.infrastructure.BridgeErrorCodes
import com.getlokalapp.paymentsdk.infrastructure.SinglePendingHandoff
import com.getlokalapp.paymentsdk.json.toOrgJson
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.razorpay.Razorpay
import org.json.JSONObject

/**
 * Handoff for the single in-flight Custom UI payment. Same reasoning as
 * `:razorpay-checkout`'s process-local handoff slot — the listener isn't
 * Parcelable, so it can't ride along in the launch Intent; it's parked here
 * for [RazorpayCustomUiActivity] to pick up. Only one Custom UI payment
 * can be in flight at a time, so a single slot is sufficient.
 */
internal val razorpayCustomUiHandoff = SinglePendingHandoff<PendingCustomUiCheckout>()

internal class PendingCustomUiCheckout(
    val key: String,
    val data: JSONObject,
    val listener: RazorpayCustomUiResultListener?,
)

/**
 * Drives Razorpay's Custom UI (Razorpay.submit()) flow on Android by launching
 * [RazorpayCustomUiActivity], an internal proxy that owns the WebView
 * Razorpay requires and implements its result listener — mirrors
 * `:razorpay-checkout`'s AndroidRazorpayCheckoutClient for hosted Checkout.
 * The Activity to launch it from comes from [ActivityTracker] (`:shared`'s
 * hostcontext utility) at call time, not a host-supplied handle. Unlike
 * AndroidRazorpayCheckoutClient, this one implements no shared interface —
 * this module has no iOS counterpart or factory indirection to satisfy — so
 * it lives alongside the rest of the Android-only proxy machinery it drives.
 */
internal class AndroidRazorpayCustomUiClient {

    private var listener: RazorpayCustomUiResultListener? = null

    fun submit(config: RazorpayCustomUiConfig) {
        val activity = ActivityTracker.current
        if (activity == null) {
            listener?.onPaymentError(ACTIVITY_UNAVAILABLE_ERROR, "razorpay_activity_unavailable")
            return
        }
        val request = PendingCustomUiCheckout(
            key = config.razorpayKey,
            data = config.data.toOrgJson(),
            listener = listener,
        )
        if (!razorpayCustomUiHandoff.tryInstall(request)) {
            listener?.onPaymentError(BRIDGE_BUSY_ERROR, BridgeErrorCodes.HANDOFF_IN_PROGRESS)
            return
        }
        try {
            activity.startActivity(Intent(activity, RazorpayCustomUiActivity::class.java))
        } catch (t: Throwable) {
            razorpayCustomUiHandoff.clearIfOwned(request)
            listener?.onPaymentError(ACTIVITY_LAUNCH_ERROR, BridgeErrorCodes.ACTIVITY_LAUNCH_FAILED)
        }
    }

    fun setPaymentResultListener(listener: RazorpayCustomUiResultListener?) {
        this.listener = listener
    }

    private companion object {
        // Non-zero and distinct from Razorpay's own PAYMENT_CANCELLED (5) so
        // the orchestrator classifies it as a failure, not a cancel.
        const val ACTIVITY_UNAVAILABLE_ERROR = 2
        const val BRIDGE_BUSY_ERROR = 3
        const val ACTIVITY_LAUNCH_ERROR = 4
    }
}

/**
 * Internal proxy Activity that owns the WebView Razorpay's Custom UI
 * (submit()) flow requires as a JS bridge (never shown to the user) and
 * satisfies Razorpay's requirement that the Activity calling submit()
 * implement its result listener — mirrors RazorpayCheckoutActivity's role for
 * hosted Checkout. Keeping this here means the host never supplies a WebView,
 * never forwards onActivityResult, and only ever passes an Activity to launch
 * this one. Runs translucent with no UI of its own — which suits methods that
 * hand off to an external app (e.g. UPI Intent), where only that app's screen
 * appears. Methods needing in-app UI (card fields, OTP/3DS) aren't rendered
 * yet — that would be added in a later version.
 */
internal class RazorpayCustomUiActivity : Activity(), PaymentResultWithDataListener {

    private var razorpay: Razorpay? = null
    private var request: PendingCustomUiCheckout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val owned = razorpayCustomUiHandoff.take()
        if (owned == null) {
            // No in-flight request — e.g. the process was recreated after death
            // mid-payment and the listener is gone. Nothing to drive; bail.
            finish()
            return
        }
        request = owned

        try {
            val webView = WebView(this)
            razorpay = Razorpay(this, owned.key).apply { setWebView(webView) }
                .also { it.submit(owned.data, this) }
        } catch (t: Throwable) {
            deliverError(GENERIC_ERROR, t.message)
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

    private inline fun deliverOnce(deliver: (RazorpayCustomUiResultListener) -> Unit) {
        val owned = request ?: return
        request = null
        owned.listener?.let(deliver)
        finish()
    }

    private companion object {
        // Non-zero so the orchestrator classifies it as a failure, not a cancel.
        const val GENERIC_ERROR = 1
    }
}
