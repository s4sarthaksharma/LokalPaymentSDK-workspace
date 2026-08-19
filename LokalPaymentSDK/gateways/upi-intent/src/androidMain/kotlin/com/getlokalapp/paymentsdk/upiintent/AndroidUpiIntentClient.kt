package com.getlokalapp.paymentsdk.upiintent

import android.content.Intent
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker
import com.getlokalapp.paymentsdk.infrastructure.BridgeErrorCodes
import com.getlokalapp.paymentsdk.infrastructure.SinglePendingOperation

/**
 * The single in-flight UPI intent: its launch payload for [UpiIntentActivity] to pick up (a listener
 * isn't Parcelable, so it can't ride in the launch Intent), and the listener waiting for the result
 * until the launch is settled. See [SinglePendingOperation] for why those two lifetimes are tracked
 * separately.
 */
internal val upiIntentOperation = SinglePendingOperation<UpiIntentLaunch, UpiIntentResultListener>()

/** What the proxy needs to launch a UPI app, and nothing that outlives launching it. */
internal class UpiIntentLaunch(
    val intentUrl: String,
    val allowedApps: List<AllowedApp>,
)

/**
 * Android launcher: parks the request in [upiIntentHandoff] and starts the
 * internal proxy [UpiIntentActivity], which owns the `startActivityForResult`
 * to the UPI app and its `onActivityResult` — so the host forwards nothing.
 * The Activity to launch from comes from [ActivityTracker] (`:shared`'s
 * hostcontext utility) at call time, not a host-supplied handle. Mirrors
 * `:razorpay-customui`'s AndroidRazorpayCustomUiClient.
 */
internal class AndroidUpiIntentClient : UpiIntentClient {

    private var listener: UpiIntentResultListener? = null

    override fun launch(config: UpiIntentConfig) {
        val activity = ActivityTracker.current
        if (activity == null) {
            listener?.onFailure(ACTIVITY_UNAVAILABLE, "upi_intent_activity_unavailable")
            return
        }
        val launch = UpiIntentLaunch(intentUrl = config.intentUrl, allowedApps = config.allowedApps)
        // Refused while a launch is still unsettled, not merely while one is starting — the operation
        // slot tracks the payment now.
        val entry = upiIntentOperation.tryInstall(launch, listener)
        if (entry == null) {
            listener?.onFailure(BridgeErrorCodes.HANDOFF_IN_PROGRESS, BridgeErrorCodes.HANDOFF_IN_PROGRESS)
            return
        }
        // No-animation launch: the proxy is invisible, so the default open/close
        // slide would show as a stray swipe over the host.
        try {
            activity.startActivity(
                Intent(activity, UpiIntentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (t: Throwable) {
            // Nothing was started, so abandon the slot rather than settling a launch that never was.
            upiIntentOperation.clearIfOwned(entry)
            listener?.onFailure(BridgeErrorCodes.ACTIVITY_LAUNCH_FAILED, BridgeErrorCodes.ACTIVITY_LAUNCH_FAILED)
        }
    }

    override fun setResultListener(listener: UpiIntentResultListener?) {
        this.listener = listener
    }

    private companion object {
        const val ACTIVITY_UNAVAILABLE = "activity_unavailable"
    }
}

internal actual fun createUpiIntentClient(): UpiIntentClient = AndroidUpiIntentClient()
