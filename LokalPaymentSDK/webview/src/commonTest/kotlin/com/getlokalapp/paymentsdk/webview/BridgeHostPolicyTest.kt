package com.getlokalapp.paymentsdk.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge authorization policy, tested against the scheme/host overload of
 * [trustedWebHostOf] — the layer *below* URL parsing.
 *
 * This is the security boundary `IMPROVEMENT_01` was raised to fix: the bridge it guards can
 * report payment results, so authorization must be exact-origin, not prefix matching.
 *
 * Scope note: these tests take scheme and host directly, so they contain no platform URL
 * parser and are meaningful on **both** Android and iOS. The cases that require parsing a
 * full URL string (user-info, explicit ports, missing scheme, `javascript:`) live in the
 * per-platform parity runners instead — they cannot live here, because
 * `android.net.Uri` is a non-functional stub on `androidHostTest` and every such
 * assertion would pass vacuously. See `docs/TESTING_02_PURE_LOGIC_AND_BRIDGE_SECURITY.md`.
 */
class BridgeHostPolicyTest {

    private val trusted = trustedWebHostOf("https", "checkout.example.com")!!

    // --- accepted -----------------------------------------------------------------

    @Test
    fun `accepts an exact https origin`() {
        val host = trustedWebHostOf("https", "checkout.example.com")

        assertEquals("https", host?.scheme)
        assertEquals("checkout.example.com", host?.host)
    }

    @Test
    fun `normalizes scheme and host case`() {
        // A platform WebView can report either casing; authorization must not depend on it.
        assertEquals(trusted, trustedWebHostOf("HTTPS", "Checkout.Example.COM"))
    }

    @Test
    fun `normalizes a trailing-dot host`() {
        // "example.com." is the fully-qualified form of "example.com" and resolves to the
        // same site, so treating them as different origins would be a bypass in one
        // direction and a broken checkout in the other.
        assertEquals(trusted, trustedWebHostOf("https", "checkout.example.com."))
    }

    // --- rejected -----------------------------------------------------------------

    @Test
    fun `rejects http for an https origin`() {
        assertNull(trustedWebHostOf("http", "checkout.example.com"))
    }

    @Test
    fun `rejects every non-https scheme`() {
        listOf("http", "javascript", "data", "file", "content", "about", "upi", "").forEach { scheme ->
            assertNull(trustedWebHostOf(scheme, "checkout.example.com"), "scheme=$scheme was accepted")
        }
    }

    @Test
    fun `rejects a missing scheme`() {
        assertNull(trustedWebHostOf(null, "checkout.example.com"))
    }

    @Test
    fun `rejects a missing host`() {
        assertNull(trustedWebHostOf("https", null))
    }

    @Test
    fun `rejects a blank host`() {
        listOf("", " ", "   ", ".", "..").forEach { host ->
            assertNull(trustedWebHostOf("https", host), "host=\"$host\" was accepted")
        }
    }

    // --- the suffix attack --------------------------------------------------------

    @Test
    fun `rejects a hostname suffix attack`() {
        // The exact bug class IMPROVEMENT_01 replaced prefix matching to fix, and which
        // SDK_REVIEW.md records as still unverified. Each of these would be authorized by a
        // naive startsWith/endsWith/contains check against "checkout.example.com".
        val lookalikes = listOf(
            "checkout.example.com.evil.com",   // trusted host as a prefix of the attacker's
            "evil-checkout.example.com.co",    // trusted host embedded, different TLD
            "notcheckout.example.com",         // trailing-suffix match
            "checkout.example.company",        // trusted host is a prefix of this string
            "checkout.example.co",             // truncation
            "xn--checkout.example.com",        // punycode-prefixed lookalike
        )

        lookalikes.forEach { host ->
            val candidate = trustedWebHostOf("https", host)
            assertNotEquals(trusted, candidate, "\"$host\" was treated as the trusted origin")
            assertFalse(
                isBridgeHostAllowed(setOf(trusted), "https", host),
                "\"$host\" was authorized to call the bridge",
            )
        }
    }

    // --- allowlist behavior -------------------------------------------------------

    @Test
    fun `authorizes a host that is on the allowlist`() {
        assertTrue(isBridgeHostAllowed(setOf(trusted), "https", "checkout.example.com"))
    }

    @Test
    fun `rejects every origin when the allowlist is empty`() {
        // The documented kill-switch: an explicitly empty bridgeHosts set disables the bridge.
        assertFalse(isBridgeHostAllowed(emptySet(), "https", "checkout.example.com"))
    }

    @Test
    fun `rejects an origin absent from a non-empty allowlist`() {
        assertFalse(isBridgeHostAllowed(setOf(trusted), "https", "other.example.com"))
    }

    @Test
    fun `authorization ignores case and trailing dots via normalization`() {
        assertTrue(isBridgeHostAllowed(setOf(trusted), "HTTPS", "Checkout.Example.COM."))
    }

    @Test
    fun `an unparseable origin fails closed`() {
        assertFalse(isBridgeHostAllowed(setOf(trusted), null, null))
        assertFalse(isBridgeHostAllowed(setOf(trusted), "https", ""))
    }

    // --- TrustedWebHost as a Set key ----------------------------------------------

    @Test
    fun `equal hosts share a hash code so Set lookup works`() {
        val a = trustedWebHostOf("https", "checkout.example.com")!!
        val b = trustedWebHostOf("HTTPS", "CHECKOUT.EXAMPLE.COM.")!!

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(b in setOf(a))
    }

    @Test
    fun `differs on scheme or host`() {
        val host = trustedWebHostOf("https", "checkout.example.com")!!

        assertNotEquals(host, trustedWebHostOf("https", "other.example.com"))
        assertNotEquals<Any?>(host, null)
        assertNotEquals<Any?>(host, "https://checkout.example.com")
    }

    @Test
    fun `toString renders the origin`() {
        assertEquals("https://checkout.example.com", trusted.toString())
    }
}
