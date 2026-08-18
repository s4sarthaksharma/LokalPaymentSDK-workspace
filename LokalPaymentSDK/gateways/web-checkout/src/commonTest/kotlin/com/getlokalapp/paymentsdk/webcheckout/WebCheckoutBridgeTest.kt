package com.getlokalapp.paymentsdk.webcheckout

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.json.lenientJson
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.paymentsdk.testkit.RecordingLogger
import com.getlokalapp.paymentsdk.webview.JsBridgeHandler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The hosted checkout page's event vocabulary, mapped to terminal results.
 *
 * `webCheckoutHandlers` builds a plain [JsBridgeHandler] per event, so the whole mapping is
 * testable by calling `onMessage` directly — no WebView, no page, no platform.
 *
 * Every outcome here is **advisory**: the page's report is a client-side claim and the host
 * must still confirm with its own backend. That is why the tests assert the mapping only, and
 * never treat `SUCCESS` as proof of payment.
 */
class WebCheckoutBridgeTest {

    private lateinit var logger: RecordingLogger

    @BeforeTest
    fun installLogger() {
        logger = RecordingLogger()
        LokalPaymentSdk.setLogger(logger)
    }

    @AfterTest
    fun removeLogger() {
        LokalPaymentSdk.setLogger(null)
    }

    /** Dispatches [payload] to the handler named [event] and returns what it reported. */
    private fun report(event: String, payload: String = "{}"): PaymentResult {
        val results = mutableListOf<PaymentResult>()
        val handler = webCheckoutHandlers { results += it }.single { it.name == event }

        handler.onMessage(payload) { /* the RN contract is fire-and-forget */ }

        assertEquals(1, results.size, "$event reported ${results.size} results")
        return results.single()
    }

    // --- the handler set -------------------------------------------------------------

    @Test
    fun `exposes exactly the seven page events under unique names`() {
        val handlers = webCheckoutHandlers { }
        val names = handlers.map { it.name }

        assertEquals(
            setOf(
                "PAYMENT_SUCCESS",
                "PAYMENT_FAILED",
                "PAYMENT_PROCESSING",
                "PAYMENT_EXPIRED",
                "PAYMENT_PENDING",
                "PAYMENT_CANCELLED",
                "PAYMENT_GATEWAY_ERROR",
            ),
            names.toSet(),
        )
        // Unique names matter: BridgeDispatcher routes via associateBy, so a collision would
        // silently drop an event.
        assertEquals(names.size, names.toSet().size, "Duplicate handler names: $names")
    }

    // --- event mapping ---------------------------------------------------------------

    @Test
    fun `success carries the page payload through verbatim`() {
        val result = report("PAYMENT_SUCCESS", """{"order_id":"order_1","provider":"dodo"}""")

        assertIs<PaymentResult.Success>(result)
        assertEquals(lenientJson.parseToJsonElement("""{"order_id":"order_1","provider":"dodo"}"""), result.gatewayData)
    }

    @Test
    fun `processing and pending are pending rather than success`() {
        listOf("PAYMENT_PROCESSING", "PAYMENT_PENDING").forEach { event ->
            assertIs<PaymentResult.Pending>(report(event), "$event should be Pending")
        }
    }

    @Test
    fun `failed uses the payload status as its code`() {
        val result = report("PAYMENT_FAILED", """{"status":"card_declined"}""")

        assertIs<PaymentResult.Failure>(result)
        assertEquals("card_declined", result.code)
        assertEquals("payment_failed", result.message)
    }

    @Test
    fun `failed falls back to a generic code when the payload has no status`() {
        val result = report("PAYMENT_FAILED", "{}")

        assertIs<PaymentResult.Failure>(result)
        assertEquals("failed", result.code)
    }

    @Test
    fun `expired is a failure with a stable code`() {
        val result = report("PAYMENT_EXPIRED", """{"status":"whatever"}""")

        assertIs<PaymentResult.Failure>(result)
        assertEquals("expired", result.code)
        assertEquals("payment_expired", result.message)
    }

    @Test
    fun `gateway error uses the payload reason as its code`() {
        val result = report("PAYMENT_GATEWAY_ERROR", """{"reason":"provider_timeout"}""")

        assertIs<PaymentResult.Failure>(result)
        assertEquals("provider_timeout", result.code)
        assertEquals("payment_gateway_error", result.message)
    }

    @Test
    fun `gateway error falls back to a generic code`() {
        val result = report("PAYMENT_GATEWAY_ERROR", "{}")

        assertIs<PaymentResult.Failure>(result)
        assertEquals("gateway_error", result.code)
    }

    @Test
    fun `cancelled is a cancellation rather than a failure`() {
        assertEquals(PaymentResult.Cancelled(CancelReason.USER_DISMISSED), report("PAYMENT_CANCELLED"))
    }

    @Test
    fun `failure events preserve the page payload as the gateway blob`() {
        val result = report("PAYMENT_FAILED", """{"status":"card_declined","provider_ref":"ch_1"}""")

        assertIs<PaymentResult.Failure>(result)
        assertTrue(
            "provider_ref" in (result.gatewayData?.keys ?: emptySet()),
            "The page payload was not preserved: ${result.gatewayData}",
        )
    }

    // --- hostile and malformed payloads ----------------------------------------------

    @Test
    fun `a malformed payload still reports with an empty blob`() {
        // The page is untrusted input. Dropping the event would leave the flow open forever, so
        // the handler must still settle — just with nothing in the blob.
        listOf("not json", "", "{", """{"unterminated": """).forEach { payload ->
            val result = report("PAYMENT_SUCCESS", payload)

            assertIs<PaymentResult.Success>(result, "payload=$payload")
            assertEquals(0, result.gatewayData.size, "payload=$payload produced a non-empty blob")
        }
    }

    @Test
    fun `a non-object payload falls back to an empty blob`() {
        listOf(""""a string"""", "42", "[1,2]", "null").forEach { payload ->
            val result = report("PAYMENT_SUCCESS", payload)

            assertIs<PaymentResult.Success>(result, "payload=$payload")
            assertEquals(0, result.gatewayData.size, "payload=$payload produced a non-empty blob")
        }
    }

    @Test
    fun `a non-string status is ignored rather than coerced`() {
        val result = report("PAYMENT_FAILED", """{"status":{"nested":"object"}}""")

        assertIs<PaymentResult.Failure>(result)
        assertEquals("failed", result.code)
    }

    @Test
    fun `each handler reports exactly once per message`() {
        val results = mutableListOf<PaymentResult>()
        val handler = webCheckoutHandlers { results += it }.single { it.name == "PAYMENT_SUCCESS" }

        handler.onMessage("{}") { }

        assertEquals(1, results.size)
    }

    // --- the injected shim ------------------------------------------------------------

    @Test
    fun `the react native shim relays to the Lokal bridge and is idempotent`() {
        // The hosted page already speaks window.ReactNativeWebView; the shim adapts it without
        // baking that knowledge into :webview.
        assertTrue("window.ReactNativeWebView" in REACT_NATIVE_BRIDGE_SHIM)
        assertTrue("window.LokalBridge.postMessage" in REACT_NATIVE_BRIDGE_SHIM)
        // Guard against double injection on navigation.
        assertTrue("__lokal" in REACT_NATIVE_BRIDGE_SHIM)
        // A page error inside the shim must not break the page.
        assertTrue("try" in REACT_NATIVE_BRIDGE_SHIM && "catch" in REACT_NATIVE_BRIDGE_SHIM)
    }

    // --- config ----------------------------------------------------------------------

    @Test
    fun `config decodes the gateway url wire key`() {
        // NOTE: `gateway_url` is marked in WebCheckoutConfig as assumed pending backend
        // confirmation. This is the single place to change if the backend names it differently.
        val config = lenientJson.decodeFromString(
            WebCheckoutConfig.serializer(),
            """{"gateway_url":"https://checkout.example.com/pay/abc","order_row_id":7}""",
        )

        assertEquals("https://checkout.example.com/pay/abc", config.gatewayUrl)
    }
}
