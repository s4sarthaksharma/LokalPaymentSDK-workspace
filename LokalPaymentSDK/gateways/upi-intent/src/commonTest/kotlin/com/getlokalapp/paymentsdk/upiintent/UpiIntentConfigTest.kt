package com.getlokalapp.paymentsdk.upiintent

import com.getlokalapp.paymentsdk.json.lenientJson
import com.getlokalapp.paymentsdk.upi.UpiApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UpiIntentConfig] decoding plus the two derived helpers. [toChooserApps] is the interesting
 * one: it filters detected apps against a backend allow-list **without per-platform
 * branching**, relying on a detected [UpiApp] carrying exactly one identifier — `packageName`
 * on Android, `urlScheme` on iOS. That assumption is load-bearing and untested until now.
 */
class UpiIntentConfigTest {

    private fun decode(json: String) = lenientJson.decodeFromString(UpiIntentConfig.serializer(), json)

    private fun androidApp(name: String, pkg: String) =
        UpiApp(displayName = name, packageName = pkg, urlScheme = null)

    private fun iosApp(name: String, scheme: String) =
        UpiApp(displayName = name, packageName = null, urlScheme = scheme)

    // --- decoding ------------------------------------------------------------------

    @Test
    fun `decodes the backend wire keys`() {
        val config = decode(
            """
            {
              "intent_url": "upi://pay?tr=TXN1",
              "txn_ref": "TXN1",
              "allowed_apps": [
                {"name": "PhonePe", "package_name": "com.phonepe.app", "url_scheme": "phonepe", "logo_url": "https://cdn/p.png"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("upi://pay?tr=TXN1", config.intentUrl)
        assertEquals("TXN1", config.txnRef)
        val allowed = config.allowedApps.single()
        assertEquals("PhonePe", allowed.name)
        assertEquals("com.phonepe.app", allowed.packageName)
        assertEquals("phonepe", allowed.urlScheme)
        assertEquals("https://cdn/p.png", allowed.logoUrl)
    }

    @Test
    fun `decodes with only the required field`() {
        val config = decode("""{"intent_url":"upi://pay?tr=TXN1"}""")

        assertNull(config.txnRef)
        assertEquals(emptyList(), config.allowedApps)
    }

    @Test
    fun `tolerates unknown sibling fields from the backend`() {
        val config = decode("""{"intent_url":"upi://pay","order_row_id":42,"experiment":{"b":"c"}}""")

        assertEquals("upi://pay", config.intentUrl)
    }

    // --- resolveTxnRef --------------------------------------------------------------

    @Test
    fun `prefers the explicit txn_ref field`() {
        val config = decode("""{"intent_url":"upi://pay?tr=FROM_URL","txn_ref":"EXPLICIT"}""")

        assertEquals("EXPLICIT", config.resolveTxnRef())
    }

    @Test
    fun `falls back to the tr param in the launch url`() {
        val config = decode("""{"intent_url":"upi://pay?pa=m@bank&tr=FROM_URL"}""")

        assertEquals("FROM_URL", config.resolveTxnRef())
    }

    @Test
    fun `falls back to empty when neither is present`() {
        // Documented as a correlation convenience only — the host already holds its own
        // reference from order creation, so this must not throw.
        val config = decode("""{"intent_url":"upi://pay?pa=m@bank"}""")

        assertEquals("", config.resolveTxnRef())
    }

    // --- toChooserApps --------------------------------------------------------------

    @Test
    fun `an empty allow-list passes every app through with no logo`() {
        val detected = listOf(androidApp("PhonePe", "com.phonepe.app"), androidApp("GPay", "com.google.android.apps.nbu.paisa.user"))

        val chooser = detected.toChooserApps(emptyList())

        assertEquals(detected, chooser.map { it.app })
        assertTrue(chooser.all { it.logoUrl == null })
    }

    @Test
    fun `matches Android apps on package name`() {
        val detected = listOf(androidApp("PhonePe", "com.phonepe.app"), androidApp("Other", "com.other.app"))
        val allowed = listOf(AllowedApp(name = "PhonePe", packageName = "com.phonepe.app", logoUrl = "https://cdn/p.png"))

        val chooser = detected.toChooserApps(allowed)

        assertEquals(listOf("com.phonepe.app"), chooser.map { it.app.packageName })
        assertEquals("https://cdn/p.png", chooser.single().logoUrl)
    }

    @Test
    fun `matches iOS apps on url scheme`() {
        val detected = listOf(iosApp("PhonePe", "phonepe"), iosApp("Other", "other"))
        val allowed = listOf(AllowedApp(name = "PhonePe", urlScheme = "phonepe", logoUrl = "https://cdn/p.png"))

        val chooser = detected.toChooserApps(allowed)

        assertEquals(listOf("phonepe"), chooser.map { it.app.urlScheme })
        assertEquals("https://cdn/p.png", chooser.single().logoUrl)
    }

    @Test
    fun `one payload serves both platforms`() {
        // The reason toChooserApps needs no platform branching: an entry carrying both ids
        // matches whichever identifier the detected app happens to have.
        val allowed = listOf(AllowedApp(name = "PhonePe", packageName = "com.phonepe.app", urlScheme = "phonepe"))

        assertEquals(1, listOf(androidApp("PhonePe", "com.phonepe.app")).toChooserApps(allowed).size)
        assertEquals(1, listOf(iosApp("PhonePe", "phonepe")).toChooserApps(allowed).size)
    }

    @Test
    fun `drops a detected app that is not on the allow-list`() {
        val detected = listOf(androidApp("Unlisted", "com.unlisted.app"))

        assertEquals(emptyList(), detected.toChooserApps(listOf(AllowedApp(packageName = "com.phonepe.app"))))
    }

    @Test
    fun `attaches a null logo when the allow-list entry carries none`() {
        val chooser = listOf(androidApp("PhonePe", "com.phonepe.app"))
            .toChooserApps(listOf(AllowedApp(packageName = "com.phonepe.app")))

        assertNull(chooser.single().logoUrl)
    }

    @Test
    fun `an allow-list entry with no identifiers matches nothing`() {
        // Guards the associateBy assumption in toChooserApps' kdoc: "neither lookup map has a
        // null key". An entry with both ids null must not become a wildcard that authorizes an
        // app whose own ids are null.
        val allowed = listOf(AllowedApp(name = "Misconfigured"))
        val nullIdApp = UpiApp(displayName = "Odd", packageName = null, urlScheme = null)

        assertEquals(emptyList(), listOf(nullIdApp).toChooserApps(allowed))
        assertEquals(emptyList(), listOf(androidApp("PhonePe", "com.phonepe.app")).toChooserApps(allowed))
    }

    @Test
    fun `preserves the detection order of surviving apps`() {
        // The chooser renders this list, so ordering is user-visible.
        val detected = listOf(
            androidApp("A", "com.a"),
            androidApp("B", "com.b"),
            androidApp("C", "com.c"),
        )
        val allowed = listOf(AllowedApp(packageName = "com.c"), AllowedApp(packageName = "com.a"))

        val chooser = detected.toChooserApps(allowed)

        assertEquals(listOf("com.a", "com.c"), chooser.map { it.app.packageName })
    }
}
