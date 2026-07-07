package com.getlokalapp.paymentsdk.razorpay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.razorpay.Razorpay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.json.JSONArray
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
 * Drives Razorpay's UPI Intent flow on Android by launching
 * [RazorpayUpiIntentActivity], an internal proxy that owns the WebView
 * Razorpay requires and implements its result listener — mirrors
 * `:razorpay-checkout`'s AndroidRazorpayCheckoutClient for hosted Checkout.
 * Unlike that client, this one implements no shared interface — this
 * module has no iOS counterpart or factory indirection to satisfy — so it
 * lives alongside the rest of the Android-only proxy machinery it drives.
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

// Deliberately not shared with `:razorpay-checkout`'s identical-looking
// conversion — a file of the same name in the same package in a sibling
// module would compile to a duplicate JVM class name
// (JsonObjectConversionsKt) and collide for any consumer depending on both
// modules. Small, self-contained duplication is the price of the two
// modules not depending on each other.

/**
 * Razorpay's Android Razorpay.submit() takes org.json.JSONObject, not
 * kotlinx.serialization's JsonObject — this bridges the opaque gatewayConfig
 * blob across without the SDK ever parsing its contents.
 */
internal fun JsonObject.toOrgJson(): JSONObject {
    val result = JSONObject()
    for ((key, value) in this) {
        result.put(key, value.toOrgJsonValue())
    }
    return result
}

private fun JsonElement.toOrgJsonValue(): Any = when (this) {
    is JsonNull -> JSONObject.NULL
    is JsonObject -> toOrgJson()
    is JsonArray -> JSONArray().also { array -> forEach { array.put(it.toOrgJsonValue()) } }
    is JsonPrimitive -> toOrgJsonPrimitive()
}

private fun JsonPrimitive.toOrgJsonPrimitive(): Any {
    booleanOrNull?.let { return it }
    longOrNull?.let { return it }
    doubleOrNull?.let { return it }
    return content
}
