package com.getlokalapp.paymentsdk.upi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker

/**
 * Android actual: discovers UPI apps dynamically by asking [PackageManager]
 * which activities can handle the standard `upi://pay` deep link. This finds
 * every installed UPI app (not just a hardcoded set), including ones released
 * after this SDK build.
 *
 * Requires the `<queries>` element for `upi://pay` in this module's manifest so
 * the packages stay visible under Android 11+ package-visibility filtering.
 */
internal actual fun detectInstalledUpiApps(): List<UpiApp> {
    val context: Context = ActivityTracker.application ?: ActivityTracker.current ?: return emptyList()
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"))

    val resolved: List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    return resolved
        .asSequence()
        .mapNotNull { it.activityInfo?.packageName }
        .distinct()
        .map { packageName ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
            UpiApp(displayName = label, packageName = packageName, urlScheme = null)
        }
        .toList()
}
