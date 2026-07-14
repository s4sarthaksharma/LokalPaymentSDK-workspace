package com.getlokalapp.paymentsdk.upi

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS actual: iOS can't enumerate installed apps, so UPI detection is limited
 * to probing a curated catalog of known UPI URL schemes via
 * [UIApplication.canOpenURL].
 *
 * Note `canOpenURL` returns `false` for any scheme the **host app** hasn't
 * declared in its `Info.plist` under `LSApplicationQueriesSchemes`, regardless
 * of whether the app is installed. Hosts must list the schemes below (or the
 * subset they care about) there for this to report anything.
 */
internal actual fun detectInstalledUpiApps(): List<UpiApp> {
    val app = UIApplication.sharedApplication
    return UPI_APP_CATALOG.mapNotNull { entry ->
        val url = NSURL(string = "${entry.scheme}://")
        if (app.canOpenURL(url)) {
            UpiApp(displayName = entry.displayName, packageName = null, urlScheme = entry.scheme)
        } else {
            null
        }
    }
}

private data class UpiCatalogEntry(val displayName: String, val scheme: String)

/**
 * Well-known UPI apps and their iOS URL schemes — the curated probe list, the
 * same approach Razorpay (hardcoded scheme list in its binary) and Juspay
 * (`intentURIs` config) use on iOS, since the OS can't enumerate apps.
 *
 * A scheme only resolves if the **host app** also declares it in its
 * `Info.plist` under `LSApplicationQueriesSchemes`; otherwise `canOpenURL`
 * returns `false` regardless of whether the app is installed. Keep these
 * schemes in sync with that declaration. Schemes here match the host's declared
 * list (e.g. `slice-upi`, `navipay`), which can differ from an app's other
 * schemes.
 */
private val UPI_APP_CATALOG = listOf(
    UpiCatalogEntry("Google Pay", "tez"),
    UpiCatalogEntry("PhonePe", "phonepe"),
    UpiCatalogEntry("Paytm", "paytmmp"),
    UpiCatalogEntry("BHIM", "bhim"),
    UpiCatalogEntry("CRED", "credpay"),
    UpiCatalogEntry("Amazon Pay", "amazonpay"),
    UpiCatalogEntry("WhatsApp", "whatsapp"),
    UpiCatalogEntry("MobiKwik", "mobikwik"),
    UpiCatalogEntry("slice", "slice-upi"),
    UpiCatalogEntry("Navi", "navipay"),
    UpiCatalogEntry("Jupiter", "jupiter"),
    UpiCatalogEntry("super.money", "super"),
    UpiCatalogEntry("PostPe", "postpe"),
    UpiCatalogEntry("INDmoney", "indmoney"),
    UpiCatalogEntry("Airtel Thanks", "myairtel"),
    UpiCatalogEntry("Scapia", "scapia"),
    UpiCatalogEntry("Bajaj Pay", "bajajpayupi"),
    UpiCatalogEntry("ICICI iMobile Pay", "imobileappnb"),
    UpiCatalogEntry("HDFC Bank", "hdfcbanknb"),
    UpiCatalogEntry("AU Bank", "aunb"),
)
