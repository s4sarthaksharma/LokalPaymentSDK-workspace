package com.getlokalapp.paymentsdk.webview

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The shared URL fixture table `IMPROVEMENT_01`'s V2 backlog asks for:
 *
 * > Use a shared table of URL fixtures and assert that Android and iOS normalize or reject
 * > every fixture identically.
 *
 * It lives in `commonTest` as **data only** (no `@Test`), and each platform's test source set
 * runs it through its own `parseUrlAuthority` actual — `android.net.Uri` on Android,
 * `NSURLComponents` on iOS. Divergence therefore shows up as a red test on one platform,
 * which for a bridge that can report payment results is a security finding, not a test bug.
 *
 * Why the assertions cannot simply live in `commonTest`: `android.net.Uri` is a
 * non-functional stub on `androidHostTest`, and because `parseUrlAuthority` wraps it in
 * `runCatching { }.getOrNull()`, every URL silently parses to `null` there. The SDK fails
 * closed, which is the correct direction — but it means a rejection assertion would pass
 * **vacuously**. The Android runner needs Robolectric for a real `Uri`.
 *
 * Two kinds of expectation, deliberately:
 *
 * - [ACCEPTED] — the normalized identity is part of the contract, so assert it exactly.
 * - [MUST_NOT_AUTHORIZE] — hostile or genuinely platform-ambiguous input (invalid percent
 *   encoding, embedded control characters, exotic host forms). Asserting an exact
 *   accept/reject for these would encode one platform's URL-parser quirks as the spec. What
 *   actually matters is the security property: **this input must never be authorized as the
 *   trusted origin.** Whether it parses to some other host or to nothing is immaterial.
 */
internal const val TRUSTED_HOST = "checkout.example.com"

/** URLs that must authorize, paired with the exact normalized host expected. */
internal val ACCEPTED: List<Pair<String, String>> = listOf(
    "https://checkout.example.com" to TRUSTED_HOST,
    // Path, query and fragment are not part of the origin identity.
    "https://checkout.example.com/pay/step-2?order=123#section" to TRUSTED_HOST,
    // Case is normalized on both scheme and host.
    "HTTPS://Checkout.Example.COM/pay" to TRUSTED_HOST,
    // Trailing dot is the FQDN form of the same host.
    "https://checkout.example.com./pay" to TRUSTED_HOST,
    // Ports are deliberately excluded from the identity — see IMPROVEMENT_01. An explicit
    // port must therefore neither grant nor revoke authorization.
    "https://checkout.example.com:8443/pay" to TRUSTED_HOST,
    "https://checkout.example.com:443/pay" to TRUSTED_HOST,
    // An IPv4 literal is a legitimate (if unusual) host and must round-trip unchanged.
    "https://192.168.1.10/pay" to "192.168.1.10",
    // A very long URL must not be truncated into a different host.
    "https://checkout.example.com/pay?blob=${"a".repeat(4000)}" to TRUSTED_HOST,
)

/** URLs that must produce no trusted host at all. */
internal val REJECTED: List<String> = listOf(
    // Non-HTTPS schemes.
    "http://checkout.example.com/pay",
    "javascript:alert(1)",
    "data:text/html,<script>alert(1)</script>",
    "file:///etc/passwd",
    "about:blank",
    // Credentials in the URL: the classic "https://trusted@evil.com" confusion. Both
    // platforms must fail closed regardless of which host they resolve.
    "https://user:pass@checkout.example.com/pay",
    "https://user@checkout.example.com/pay",
    // No scheme.
    "//checkout.example.com/pay",
    "checkout.example.com/pay",
    "/pay",
    // No host.
    "https://",
    "https:///pay",
    // Not a URL at all.
    "",
    "   ",
    "not a url",
)

/**
 * Hostile or platform-ambiguous inputs. Only the security property is asserted: none of
 * these may be authorized as [TRUSTED_HOST].
 */
internal val MUST_NOT_AUTHORIZE: List<String> = listOf(
    // Suffix / lookalike attacks against checkout.example.com.
    "https://checkout.example.com.evil.com/pay",
    "https://evil.com/pay?next=https://checkout.example.com",
    "https://evil.com#https://checkout.example.com",
    "https://notcheckout.example.com/pay",
    "https://checkout.example.company/pay",
    // Percent-encoded separators — an attempt to smuggle the trusted host into the
    // userinfo or path while the real host is somewhere else.
    "https://checkout.example.com%2eevil.com/pay",
    "https://checkout.example.com%40evil.com/pay",
    "https://evil.com%2f@checkout.example.com/pay",
    // Invalid percent encoding.
    "https://checkout.example.com%zz/pay",
    "https://%2f%2fcheckout.example.com/pay",
    // Whitespace and control characters embedded in the authority.
    "https://checkout.example.com .evil.com/pay",
    "https://checkout.example.com\n.evil.com/pay",
    "https://checkout.example.com\t.evil.com/pay",
    "https://check out.example.com/pay",
    "https:// checkout.example.com/pay",
    // Backslash confusion.
    "https://evil.com\\@checkout.example.com/pay",
    // Unicode / punycode lookalikes: "сheckout" starts with Cyrillic es (U+0441).
    "https://сheckout.example.com/pay",
    "https://xn--heckout-ftg.example.com/pay",
)

/**
 * Runs the whole table against the current platform's `parseUrlAuthority`. Called from one
 * thin runner per platform.
 */
internal fun assertUrlAuthorityParity() {
    val trusted = trustedWebHostOf("https", TRUSTED_HOST)!!

    ACCEPTED.forEach { (url, expectedHost) ->
        val actual = trustedWebHostOf(url)
        assertEquals(
            expectedHost,
            actual?.host,
            "\"${url.take(80)}\" should authorize host \"$expectedHost\" but gave \"${actual?.host}\"",
        )
        assertEquals("https", actual?.scheme, "\"${url.take(80)}\" produced a non-https scheme")
    }

    REJECTED.forEach { url ->
        assertNull(trustedWebHostOf(url), "\"$url\" produced a trusted host but must be rejected")
    }

    MUST_NOT_AUTHORIZE.forEach { url ->
        assertNotEquals(
            trusted,
            trustedWebHostOf(url),
            "\"$url\" was authorized as the trusted origin \"$trusted\"",
        )
    }
}
