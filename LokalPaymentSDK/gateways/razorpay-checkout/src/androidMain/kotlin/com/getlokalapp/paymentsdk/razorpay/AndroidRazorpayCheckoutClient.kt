package com.getlokalapp.paymentsdk.razorpay

import android.content.Intent
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker
import com.getlokalapp.paymentsdk.infrastructure.BridgeErrorCodes
import com.getlokalapp.paymentsdk.json.toOrgJson

internal actual fun createRazorpayCheckoutClient(): RazorpayCheckoutClient = AndroidRazorpayCheckoutClient()

/**
 * Drives Razorpay Checkout on Android by launching [RazorpayCheckoutActivity],
 * an internal proxy that implements Razorpay's result listener. Razorpay
 * requires the Activity that calls Checkout.open() to implement that
 * interface, so the SDK owns that Activity rather than pushing the
 * requirement onto the host — the Activity to launch it from comes from
 * [ActivityTracker] (`:shared`'s hostcontext utility), not a host-supplied
 * handle.
 */
internal class AndroidRazorpayCheckoutClient : RazorpayCheckoutClient {

    private var listener: RazorpayPaymentResultListener? = null

    override fun openCheckout(config: RazorpayCheckoutConfig) {
        val activity = ActivityTracker.current
        if (activity == null) {
            listener?.onPaymentError(ACTIVITY_UNAVAILABLE_ERROR, "razorpay_activity_unavailable")
            return
        }
        val launch = CheckoutLaunch(key = config.razorpayKey, data = config.data.toOrgJson())
        // Refused while a checkout is still unsettled, not merely while one is being launched — the
        // operation slot tracks the payment now, so this also stops a second sheet opening over one
        // whose result has yet to arrive.
        val entry = razorpayCheckoutOperation.tryInstall(launch, listener)
        if (entry == null) {
            listener?.onPaymentError(BRIDGE_BUSY_ERROR, BridgeErrorCodes.HANDOFF_IN_PROGRESS)
            return
        }
        try {
            activity.startActivity(Intent(activity, RazorpayCheckoutActivity::class.java))
        } catch (t: Throwable) {
            // Nothing was started, so abandon the slot rather than settling a payment that never was.
            razorpayCheckoutOperation.clearIfOwned(entry)
            listener?.onPaymentError(ACTIVITY_LAUNCH_ERROR, BridgeErrorCodes.ACTIVITY_LAUNCH_FAILED)
        }
    }

    override fun setPaymentResultListener(listener: RazorpayPaymentResultListener?) {
        this.listener = listener
    }

    private companion object {
        // Non-zero so the orchestrator classifies it as a failure, not a cancel.
        const val ACTIVITY_UNAVAILABLE_ERROR = 2
        const val BRIDGE_BUSY_ERROR = 3
        const val ACTIVITY_LAUNCH_ERROR = 4
    }
}
