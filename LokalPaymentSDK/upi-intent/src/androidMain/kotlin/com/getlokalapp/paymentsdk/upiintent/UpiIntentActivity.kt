package com.getlokalapp.paymentsdk.upiintent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View

/**
 * Internal translucent proxy Activity that launches the UPI intent with
 * `startActivityForResult` and receives its `onActivityResult` — so the host
 * never supplies an Activity for results nor forwards anything. Runs with no UI
 * of its own; only the chosen UPI app's screen appears. Mirrors
 * `:razorpay-customui`'s RazorpayCustomUiActivity role.
 *
 * Result policy (a deliberate product decision): once control has been handed
 * to a UPI app, the on-device outcome is unverifiable, so **any** return —
 * success, failure, or a user back-out — maps to
 * [com.getlokalapp.paymentsdk.model.PaymentResult.Pending] with the app's
 * unverified status as a hint; the host resolves the real result via its
 * backend. A [com.getlokalapp.paymentsdk.model.PaymentResult.Failure] is
 * emitted only when no UPI app could be launched at all.
 */
internal class UpiIntentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        makeInvisible()

        val pending = UpiIntentBridge.pending
        if (pending == null) {
            // No in-flight request — e.g. the process was recreated after death
            // mid-payment and the listener is gone. Nothing to drive; bail.
            finish()
            return
        }

        // Launch once; on configuration-change recreation the UPI app is already
        // in front and its result will arrive at onActivityResult.
        if (savedInstanceState == null) {
            try {
                startActivityForResult(Intent(Intent.ACTION_VIEW, Uri.parse(pending.intentUrl)), REQ_UPI)
            } catch (e: ActivityNotFoundException) {
                deliver { onFailure(NO_UPI_APP, "no_upi_app_installed") }
            } catch (t: Throwable) {
                deliver { onFailure(LAUNCH_FAILED, t.message ?: "upi_intent_launch_failed") }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UPI) return
        // Handed off → always Pending, whatever the resultCode. The "response"
        // extra (when present) is a soft hint only; absent on many apps.
        val response = data?.getStringExtra(EXTRA_RESPONSE)
        deliver { onPending(parseClientStatus(response)) }
    }

    /**
     * Makes the proxy window truly invisible: transparent system bars drawn
     * edge-to-edge, so the host's own status bar shows through instead of a
     * black/tinted strip. Done in code (not just the theme) because Android 15+
     * ignores `statusBarColor` and only honors edge-to-edge layout.
     */
    private fun makeInvisible() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /**
     * Suppresses the close transition so the invisible proxy doesn't slide out
     * when it finishes (the launch enter animation is already suppressed via
     * FLAG_ACTIVITY_NO_ANIMATION on the launching intent). Kept in code because
     * this module can't ship a custom theme (`windowAnimationStyle=@null`) —
     * KMP Android-library resources aren't merged into the host.
     */
    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    /** Delivers to the parked listener exactly once, clears the slot, and finishes. */
    private inline fun deliver(action: UpiIntentResultListener.() -> Unit) {
        val listener = UpiIntentBridge.pending?.listener
        UpiIntentBridge.pending = null
        listener?.action()
        finish()
    }

    private companion object {
        const val REQ_UPI = 4001
        const val EXTRA_RESPONSE = "response"
        const val NO_UPI_APP = "no_upi_app"
        const val LAUNCH_FAILED = "launch_failed"
    }
}
