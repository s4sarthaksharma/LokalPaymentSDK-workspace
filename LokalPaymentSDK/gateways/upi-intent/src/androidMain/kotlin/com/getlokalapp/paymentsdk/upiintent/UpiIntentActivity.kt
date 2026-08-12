package com.getlokalapp.paymentsdk.upiintent

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.core.view.WindowCompat
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
    private var request: PendingUpiIntent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        makeInvisible()

        val owned = upiIntentHandoff.take()
        if (owned == null) {
            // No in-flight request — e.g. the process was recreated after death
            // mid-payment and the listener is gone. Nothing to drive; bail.
            finish()
            return
        }
        request = owned

        val url = owned.intentUrl
        // An app-specific scheme (phonepe://…) already names its target — launch
        // directly, no chooser.
        if (!url.isGenericUpiScheme()) {
            launchApp(url, targetPackage = null)
            return
        }
        val installed = LokalPaymentSdk.installedUpiApps().filter { it.packageName != null }
        val allowed = owned.allowedApps
        val apps = installed.toChooserApps(allowed)
        when {
            // Backend restricted the chooser but none of those apps are installed:
            // a deliberate dead end, so fail rather than fall back to all apps.
            allowed.isNotEmpty() && apps.isEmpty() ->
                deliver { onFailure(NO_UPI_APP, "no_allowed_upi_app_installed") }
            // No allow-list and nothing detected — best-effort plain launch (a
            // single handler opens; otherwise the OS decides).
            apps.isEmpty() ->
                launchApp(url, targetPackage = null)
            else ->
                dialog = showUpiAppPicker(
                    activity = this,
                    apps = apps,
                    onPick = { chooserApp -> launchApp(url, chooserApp.app.packageName) },
                    onCancel = { deliver { onCancelled() } },
                )
        }
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
        // Draw edge-to-edge so content sits behind the system bars. WindowCompat
        // handles the version split (WindowInsetsController on R+, legacy flags below).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // statusBarColor/navigationBarColor have no non-deprecated replacement: they're
        // ignored on Android 15+ (which is transparent edge-to-edge anyway), but still
        // needed to force transparent bars below API 35.
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
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
        val owned = request ?: return
        request = null
        owned.listener?.action()
        finish()
    }

    private companion object {
        const val REQ_UPI = 4001
        const val EXTRA_RESPONSE = "response"
        const val NO_UPI_APP = "no_upi_app"
        const val LAUNCH_FAILED = "launch_failed"
    }
}
