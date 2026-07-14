package com.getlokalapp.paymentsdk.upi

/**
 * A UPI-capable payment app detected on the device by
 * [com.getlokalapp.paymentsdk.LokalPaymentSdk.installedUpiApps].
 *
 * The two identifiers are platform-specific and mutually exclusive — which one
 * is populated tells you which platform produced the result:
 *
 * - **Android** discovers apps dynamically (every app that can handle
 *   `upi://pay`), so [packageName] is set and [urlScheme] is `null`. Feed
 *   [packageName] to a gateway that takes a UPI app package (e.g. Razorpay
 *   Custom UI's `upi_app_package_name`).
 * - **iOS** can't enumerate apps; it probes a curated set of known UPI URL
 *   schemes, so [urlScheme] is set and [packageName] is `null`.
 *
 * @property displayName human-readable app name (Android: the launcher label;
 *   iOS: the catalog name).
 * @property packageName Android application id, or `null` on iOS.
 * @property urlScheme iOS URL scheme (without `://`), or `null` on Android.
 */
data class UpiApp(
    val displayName: String,
    val packageName: String?,
    val urlScheme: String?,
)

/**
 * Detects UPI apps installed on the current device. Backed per platform:
 * Android queries [android.content.pm.PackageManager] for `upi://pay`
 * handlers; iOS probes a curated scheme catalog via `UIApplication.canOpenURL`.
 */
internal expect fun detectInstalledUpiApps(): List<UpiApp>
