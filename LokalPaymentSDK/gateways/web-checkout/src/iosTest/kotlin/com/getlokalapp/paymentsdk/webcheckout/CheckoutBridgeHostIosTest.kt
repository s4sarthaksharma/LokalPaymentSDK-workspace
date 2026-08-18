package com.getlokalapp.paymentsdk.webcheckout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [checkoutBridgeHost] validates the backend-built checkout URL before any UI is created, and
 * derives the single origin allowed to call the native bridge. It is the gateway-level seam
 * over `:webview`'s policy, so a future production/staging allow-list can land here without
 * leaking that policy into the generic module.
 *
 * iOS-only for now: it resolves through `parseUrlAuthority`, whose Android actual is a
 * non-functional `android.net.Uri` stub on `androidHostTest`. Every URL would parse to null
 * there, so the rejection cases would pass vacuously and the acceptance cases would fail. The
 * Android half needs Robolectric — see docs/TESTING_02_PURE_LOGIC_AND_BRIDGE_SECURITY.md.
 */
class CheckoutBridgeHostIosTest {

    @Test
    fun `accepts an absolute https checkout url and returns its origin`() {
        val host = checkoutBridgeHost("https://checkout.example.com/pay/abc?token=1")

        assertEquals("https", host?.scheme)
        assertEquals("checkout.example.com", host?.host)
    }

    @Test
    fun `the origin excludes path and query and port`() {
        assertEquals(
            checkoutBridgeHost("https://checkout.example.com/pay"),
            checkoutBridgeHost("https://checkout.example.com:8443/other?x=1#y"),
        )
    }

    @Test
    fun `rejects a non-https url`() {
        assertNull(checkoutBridgeHost("http://checkout.example.com/pay"))
    }

    @Test
    fun `rejects a url carrying credentials`() {
        assertNull(checkoutBridgeHost("https://user:pass@checkout.example.com/pay"))
    }

    @Test
    fun `rejects a relative or malformed url`() {
        listOf("", "   ", "/pay", "checkout.example.com/pay", "not a url", "javascript:alert(1)").forEach { url ->
            assertNull(checkoutBridgeHost(url), "\"$url\" produced a bridge host")
        }
    }
}
