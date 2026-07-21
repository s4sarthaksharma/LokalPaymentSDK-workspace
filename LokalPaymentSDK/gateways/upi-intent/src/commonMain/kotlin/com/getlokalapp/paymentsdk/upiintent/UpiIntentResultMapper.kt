package com.getlokalapp.paymentsdk.upiintent

import com.getlokalapp.paymentsdk.model.ClientStatus

/**
 * Extracts a single query param from either a full `upi://…?a=1&b=2` URL or a
 * bare `a=1&b=2` query string (the UPI intent response string has no leading
 * `?`). Key match is case-insensitive so it copes with apps that vary casing
 * (`Status` vs `status`). Returns null if absent. No platform URL parser needed
 * — this stays in commonMain and is unit-testable.
 */
internal fun String.upiParam(key: String): String? {
    val query = if ('?' in this) substringAfter('?') else this
    return query.split('&').firstNotNullOfOrNull { pair ->
        val eq = pair.indexOf('=')
        if (eq > 0 && pair.substring(0, eq).equals(key, ignoreCase = true)) {
            pair.substring(eq + 1)
        } else {
            null
        }
    }
}

/**
 * Maps the `Status` a UPI app reported in its Android intent response to a
 * [ClientStatus] hint. This is UX flavor only and never authoritative (see
 * [ClientStatus]): a null/empty/unparseable response — the common case, since
 * many apps return nothing — is [ClientStatus.UNKNOWN], as are non-terminal
 * values like `SUBMITTED`/`PENDING`.
 */
internal fun parseClientStatus(response: String?): ClientStatus =
    when (response?.upiParam("Status")?.uppercase()) {
        "SUCCESS" -> ClientStatus.SUCCESS
        "FAILURE", "FAIL" -> ClientStatus.FAILURE
        else -> ClientStatus.UNKNOWN
    }

/**
 * Rewrites the scheme of a `upi://…` deep link to a specific UPI app's scheme
 * (e.g. `upi://mandate?…` → `phonepe://mandate?…`), keeping host + query
 * intact. Used on iOS after the user picks an app in the in-SDK chooser, since
 * iOS has no OS chooser and needs the app-specific scheme to route
 * deterministically. Returns the string unchanged if it has no `://`.
 *
 * ⚠️ Plain scheme swap: some apps expect a different path under their scheme
 * (`tez://upi/pay…`) and `upi://mandate` (AutoPay) support varies per app —
 * this is the v1 approach and needs per-app device testing; a per-app template
 * can replace it here without touching callers.
 */
internal fun String.withUpiScheme(scheme: String): String {
    val schemeSep = indexOf("://")
    return if (schemeSep >= 0) "$scheme://${substring(schemeSep + 3)}" else this
}

/**
 * True only for the generic `upi://…` scheme — an ambiguous target that needs
 * app selection. False when the deep link already names a specific app
 * (`phonepe://…`, `tez://…`), which should be opened directly with no chooser.
 * Used on iOS to decide whether to present the in-SDK picker at all.
 */
internal fun String.isGenericUpiScheme(): Boolean =
    substringBefore("://", "").equals("upi", ignoreCase = true)
