package com.getlokalapp.paymentsdk.upiintent

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import com.getlokalapp.paymentsdk.LokalPaymentSdk

/**
 * Internal transparent proxy Activity that owns the UPI launch and its
 * `onActivityResult`, so the host forwards nothing. It presents the SDK's own
 * bottom-sheet chooser ([showUpiAppPicker]) and launches the picked app
 * **directly** (`Intent.setPackage`) instead of letting Android show its
 * `ResolverActivity` ("Open with…") — that system chooser is what produced the
 * black status bar and slide animation and can't be restyled.
 *
 * The chooser is a floating [Dialog], so it anchors to the bottom, dims behind,
 * and handles back / tap-outside itself → [UpiIntentResultListener.onCancelled].
 *
 * Result policy (a deliberate product decision): once control has been handed
 * to a UPI app the on-device outcome is unverifiable, so **any** return maps to
 * [com.getlokalapp.paymentsdk.model.PaymentResult.Pending] with the app's
 * unverified status as a hint; the host resolves the real result via its
 * backend. A [com.getlokalapp.paymentsdk.model.PaymentResult.Failure] is
 * emitted only when no UPI app could be launched at all.
 */
internal class UpiIntentActivity : Activity() {

    private var dialog: Dialog? = null

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

        // Only act on first creation; on recreation the launch/pick already
        // happened and the result will arrive at onActivityResult.
        if (savedInstanceState != null) return

        val url = pending.intentUrl
        // An app-specific scheme (phonepe://…) already names its target — launch
        // directly, no chooser.
        if (!url.isGenericUpiScheme()) {
            launchApp(url, targetPackage = null)
            return
        }
        val apps = LokalPaymentSdk.installedUpiApps().filter { it.packageName != null }
        if (apps.isEmpty()) {
            // Nothing detected — best-effort plain launch (a single handler opens;
            // otherwise the OS decides). No app list to choose from.
            launchApp(url, targetPackage = null)
            return
        }
        dialog = showUpiAppPicker(
            activity = this,
            apps = apps,
            onPick = { app -> launchApp(url, app.packageName) },
            onCancel = { deliver { onCancelled() } },
        )
    }

    /** Launches the UPI app for [url], targeting [targetPackage] when known so the OS never disambiguates. */
    private fun launchApp(url: String, targetPackage: String?) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (targetPackage != null) intent.setPackage(targetPackage)
            startActivityForResult(intent, REQ_UPI)
        } catch (e: ActivityNotFoundException) {
            deliver { onFailure(NO_UPI_APP, "no_upi_app_installed") }
        } catch (t: Throwable) {
            deliver { onFailure(LAUNCH_FAILED, t.message ?: "upi_intent_launch_failed") }
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
     * Makes the proxy window invisible behind the dialog: transparent system
     * bars drawn edge-to-edge, so the host's own status bar shows through
     * instead of a black/tinted strip. Done in code (not just the theme)
     * because Android 15+ ignores `statusBarColor` and only honors edge-to-edge.
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
     * Delivers to the parked listener at most once, clears the slot, and
     * finishes. Detaches the dialog's cancel listener first so dismissing it
     * here doesn't re-enter [deliver].
     */
    private inline fun deliver(action: UpiIntentResultListener.() -> Unit) {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
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
