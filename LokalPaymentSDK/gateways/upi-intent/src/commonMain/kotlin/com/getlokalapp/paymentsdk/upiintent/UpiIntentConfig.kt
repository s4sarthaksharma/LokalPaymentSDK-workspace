package com.getlokalapp.paymentsdk.upiintent

import com.getlokalapp.paymentsdk.upi.UpiApp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parsed from PaymentOrder.gatewayConfig when gateway is
 * [com.getlokalapp.paymentsdk.model.PaymentGateway.UPI_INTENT].
 *
 * [intentUrl] is the ready-to-launch `upi://…` deep link the backend built and
 * signed (a one-time `upi://pay` or an AutoPay `upi://mandate` — this module
 * never inspects or reshapes it). [txnRef] is the merchant transaction
 * reference the host will poll its backend on; it's optional here because the
 * same value is also present in the URL's `tr` query param — see
 * [resolveTxnRef]. [allowedApps] optionally restricts the in-SDK chooser to a
 * backend-curated set of UPI apps — see [AllowedApp] and [restrictToAllowed].
 */
@Serializable
internal data class UpiIntentConfig(
    @SerialName("intent_url") val intentUrl: String,
    @SerialName("txn_ref") val txnRef: String? = null,
    @SerialName("allowed_apps") val allowedApps: List<AllowedApp> = emptyList(),
)

/**
 * One entry in [UpiIntentConfig.allowedApps]: a backend-supplied UPI app the
 * chooser is allowed to offer. The backend is the source of truth for the ids,
 * so the SDK hardcodes/guesses nothing. Each platform reads only the id it
 * understands — Android matches [packageName] against dynamically-discovered
 * apps; iOS matches [urlScheme] against its detection catalog — which is why one
 * payload serves both (see [restrictToAllowed]). [name] is for backend-side
 * readability only; the chooser shows the on-device app label, not this.
 */
@Serializable
internal data class AllowedApp(
    @SerialName("name") val name: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("url_scheme") val urlScheme: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
)

/**
 * A detected [UpiApp] paired with the optional backend [logoUrl] from its
 * matching [AllowedApp] — the unit the in-SDK chooser renders. When [logoUrl] is
 * null (no allow-list, or the entry carried none) each platform falls back to
 * its own default icon: Android the OS launcher icon, iOS a monogram letter.
 */
internal data class UpiChooserApp(
    val app: UpiApp,
    val logoUrl: String?,
)

/**
 * The transaction reference to carry out on [com.getlokalapp.paymentsdk.model.PaymentResult.Pending]:
 * prefer the explicit [UpiIntentConfig.txnRef] sibling field, else pull the
 * UPI-spec `tr` param out of the launch URL. Falls back to "" if neither is
 * present — the host already holds its own reference from order creation, so
 * this is only a correlation convenience, never load-bearing.
 */
internal fun UpiIntentConfig.resolveTxnRef(): String =
    txnRef ?: intentUrl.upiParam("tr") ?: ""

/**
 * Restricts a list of detected [UpiApp]s to the backend-supplied [allowed] set
 * and pairs each survivor with that entry's [AllowedApp.logoUrl]. An empty
 * [allowed] means "no restriction" — every app passes through with a null logo.
 *
 * The match works on both platforms without per-platform branching because a
 * detected [UpiApp] carries exactly one id: Android apps have only
 * [UpiApp.packageName] (matched against the allow-list's `package_name`s) and
 * iOS apps have only [UpiApp.urlScheme] (matched against its `url_scheme`s). The
 * other field is `null`, and neither lookup map has a null key, so each platform
 * effectively resolves on its own id.
 */
internal fun List<UpiApp>.toChooserApps(allowed: List<AllowedApp>): List<UpiChooserApp> {
    if (allowed.isEmpty()) return map { UpiChooserApp(it, logoUrl = null) }
    val byPackage = allowed.filter { it.packageName != null }.associateBy { it.packageName }
    val byScheme = allowed.filter { it.urlScheme != null }.associateBy { it.urlScheme }
    return mapNotNull { app ->
        val match = byPackage[app.packageName] ?: byScheme[app.urlScheme] ?: return@mapNotNull null
        UpiChooserApp(app, logoUrl = match.logoUrl)
    }
}
