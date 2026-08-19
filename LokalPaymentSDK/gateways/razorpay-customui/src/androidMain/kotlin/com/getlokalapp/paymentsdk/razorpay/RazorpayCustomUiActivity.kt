package com.getlokalapp.paymentsdk.razorpay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.webkit.WebView
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker
import com.getlokalapp.paymentsdk.infrastructure.BridgeErrorCodes
import com.getlokalapp.paymentsdk.infrastructure.OperationProxy
import com.getlokalapp.paymentsdk.infrastructure.SinglePendingOperation
import com.getlokalapp.paymentsdk.json.toOrgJson
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.razorpay.Razorpay
import org.json.JSONObject

/**
 * The single in-flight Custom UI payment: its launch payload for [RazorpayCustomUiActivity] to pick up
 * (a listener isn't Parcelable, so none of it can ride along in the launch Intent), and the listener
 * waiting for the result until the payment is settled. See [SinglePendingOperation] for why those two
 * lifetimes are tracked separately.
 */
internal val razorpayCustomUiOperation =
    SinglePendingOperation<CustomUiLaunch, RazorpayCustomUiResultListener>()

/** What the proxy needs to submit the payment, and nothing that outlives submitting it. */
internal class CustomUiLaunch(
    val key: String,
    val data: JSONObject,
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
        val launch = CustomUiLaunch(key = config.razorpayKey, data = config.data.toOrgJson())
        // Refused while a payment is still unsettled, not merely while one is being launched — the
        // operation slot tracks the payment now.
        val entry = razorpayCustomUiOperation.tryInstall(launch, listener)
        if (entry == null) {
            listener?.onPaymentError(BRIDGE_BUSY_ERROR, BridgeErrorCodes.HANDOFF_IN_PROGRESS)
            return
        }
        try {
            activity.startActivity(Intent(activity, RazorpayCustomUiActivity::class.java))
        } catch (t: Throwable) {
            // Nothing was started, so abandon the slot rather than settling a payment that never was.
            razorpayCustomUiOperation.clearIfOwned(entry)
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
internal class RazorpayCustomUiActivity : ComponentActivity(), PaymentResultWithDataListener {

    private var razorpay: Razorpay? = null

    /**
     * Owns when this payment is settled, including the case Razorpay cannot report: the system
     * destroying this proxy mid-payment while the process lives on. This flow hands off to external UPI
     * apps, so being backgrounded and reclaimed is an ordinary thing to happen.
     */
    private val proxy = OperationProxy(this, razorpayCustomUiOperation, TAG) { listener ->
        listener.onUiDestroyed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launch = proxy.takeLaunchOrSettle() ?: return

        try {
            val webView = WebView(this)
            razorpay = Razorpay(this, launch.key).apply { setWebView(webView) }
                .also { it.submit(launch.data, this) }
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
        const val TAG = "RazorpayCustomUi"

        // Non-zero so the orchestrator classifies it as a failure, not a cancel.
        const val GENERIC_ERROR = 1
    }
}
