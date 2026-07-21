package com.getlokalapp.paymentsdk.upiintent

import android.content.Intent
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker

/**
 * Handoff for the single in-flight UPI intent. Same reasoning as
 * `:razorpay-customui`'s RazorpayCustomUiBridge — the listener isn't
 * Parcelable so it can't ride in the launch Intent; it's parked here for
 * [UpiIntentActivity] to pick up. Only one UPI intent can be in flight at a
 * time, so a single slot suffices.
 */
internal object UpiIntentBridge {
    @Volatile
    var pending: PendingUpiIntent? = null
}

internal class PendingUpiIntent(
    val intentUrl: String,
    val allowedApps: List<AllowedApp>,
    val listener: UpiIntentResultListener?,
)

/**
 * Android launcher: parks the request in [UpiIntentBridge] and starts the
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
        UpiIntentBridge.pending = PendingUpiIntent(
            intentUrl = config.intentUrl,
            allowedApps = config.allowedApps,
            listener = listener,
        )
        // No-animation launch: the proxy is invisible, so the default open/close
        // slide would show as a stray swipe over the host.
        activity.startActivity(
            Intent(activity, UpiIntentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    override fun setResultListener(listener: UpiIntentResultListener?) {
        this.listener = listener
    }

    private companion object {
        const val ACTIVITY_UNAVAILABLE = "activity_unavailable"
    }
}

internal actual fun createUpiIntentClient(): UpiIntentClient = AndroidUpiIntentClient()
