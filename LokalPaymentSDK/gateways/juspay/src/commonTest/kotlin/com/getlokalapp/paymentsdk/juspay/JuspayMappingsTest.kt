package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.paymentsdk.testkit.jsonObjectOf
import com.getlokalapp.paymentsdk.testkit.jsonOf
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Classification of HyperSDK's `process_result` object.
 *
 * Two properties matter beyond the status mapping itself. First, a successful or pending
 * outcome must carry the vendor object through **unmodified** — the host forwards it to its
 * backend, so any reshaping here would corrupt verification. Second, `status` can arrive either
 * at the top level or nested under `payload`, and every lookup has to survive a malformed
 * object rather than throwing inside a vendor callback.
 */
class JuspayMappingsTest {

    private fun processResult(status: String, vararg extra: Pair<String, String>) =
        jsonOf("status" to status, *extra)

    // --- status classification ------------------------------------------------------

    @Test
    fun `charged is a success carrying the vendor object unmodified`() {
        val vendor = processResult("charged", "orderId" to "order_1", "txnId" to "txn_1")

        val result = juspayResultToPaymentResult(vendor)

        assertIs<PaymentResult.Success>(result)
        // Identity of content, not a rebuilt subset: the host's backend verifies this blob.
        assertEquals(vendor, result.gatewayData)
    }

    @Test
    fun `authorizing and pending_vbv are pending rather than success`() {
        // Both mean the outcome is not yet known; reporting success would tell a host to
        // deliver goods for an unconfirmed payment.
        listOf("authorizing", "pending_vbv").forEach { status ->
            val vendor = processResult(status)
            val result = juspayResultToPaymentResult(vendor)

            assertIs<PaymentResult.Pending>(result, "status=$status")
            assertEquals(vendor, result.gatewayData)
        }
    }

    @Test
    fun `backpressed and user_aborted are cancellations`() {
        listOf("backpressed", "user_aborted").forEach { status ->
            assertEquals(
                PaymentResult.Cancelled(CancelReason.USER_DISMISSED),
                juspayResultToPaymentResult(processResult(status)),
                "status=$status",
            )
        }
    }

    @Test
    fun `status matching is case-insensitive`() {
        // JuspayStatus.fromWire is documented as case-insensitive; the wire values are
        // lowercase, but a vendor upgrade changing casing must not silently become a failure.
        assertIs<PaymentResult.Success>(juspayResultToPaymentResult(processResult("CHARGED")))
        assertIs<PaymentResult.Success>(juspayResultToPaymentResult(processResult("Charged")))
        assertEquals(JuspayStatus.CHARGED, JuspayStatus.fromWire("cHaRgEd"))
    }

    @Test
    fun `every declared status maps to a non-failure outcome`() {
        // Iterates the enum, so a status added later is covered without editing this test —
        // it will fail until the new entry is classified in juspayResultToPaymentResult.
        JuspayStatus.entries.forEach { status ->
            val result = juspayResultToPaymentResult(processResult(status.wire))

            assertIs<PaymentResult>(result)
            check(result !is PaymentResult.Failure) { "${status.wire} classified as a failure" }
        }
    }

    // --- unrecognized statuses ------------------------------------------------------

    @Test
    fun `an unknown status is a failure using the vendor error fields`() {
        val vendor = processResult(
            "some_new_status",
            "errorCode" to "JP_001",
            "errorMessage" to "Card declined by issuer",
        )

        val result = juspayResultToPaymentResult(vendor)

        assertIs<PaymentResult.Failure>(result)
        assertEquals("JP_001", result.code)
        assertEquals("Card declined by issuer", result.message)
        // The vendor object is preserved for a gateway-originated failure.
        assertEquals(vendor, result.gatewayData)
    }

    @Test
    fun `an unknown status with no error code falls back to the status string`() {
        val result = juspayResultToPaymentResult(processResult("some_new_status"))

        assertIs<PaymentResult.Failure>(result)
        assertEquals("some_new_status", result.code)
        assertEquals("Juspay payment failed (status=some_new_status)", result.message)
    }

    @Test
    fun `a missing status is a failure that says so`() {
        val result = juspayResultToPaymentResult(jsonOf("orderId" to "order_1"))

        assertIs<PaymentResult.Failure>(result)
        assertEquals("", result.code)
        assertEquals("Juspay payment failed (status=)", result.message)
    }

    // --- nested payload handling ----------------------------------------------------

    @Test
    fun `reads status from the nested payload object in preference to the top level`() {
        // HyperSDK nests the real result under "payload"; a stale top-level value must not win.
        val vendor = jsonObjectOf(
            "status" to JsonPrimitive("some_new_status"),
            "payload" to jsonOf("status" to "charged"),
        )

        assertIs<PaymentResult.Success>(juspayResultToPaymentResult(vendor))
        assertEquals("charged", vendor.juspayStatus())
    }

    @Test
    fun `falls back to the top-level status when payload is absent`() {
        assertEquals("charged", jsonOf("status" to "charged").juspayStatus())
    }

    @Test
    fun `falls back when payload is present but not an object`() {
        // Must not throw inside a vendor callback.
        val vendor = jsonObjectOf(
            "payload" to JsonPrimitive("unexpectedly a string"),
            "status" to JsonPrimitive("charged"),
        )

        assertEquals("charged", vendor.juspayStatus())
        assertIs<PaymentResult.Success>(juspayResultToPaymentResult(vendor))
    }

    @Test
    fun `a non-primitive status is treated as absent`() {
        val vendor = jsonObjectOf("status" to jsonOf("nested" to "object"))

        assertEquals("", vendor.juspayStatus())
        assertIs<PaymentResult.Failure>(juspayResultToPaymentResult(vendor))
    }

    @Test
    fun `a JSON null status is treated as absent`() {
        assertEquals("", jsonObjectOf("status" to JsonNull).juspayStatus())
    }

    @Test
    fun `error fields are read only from the top level`() {
        // juspayErrorCode/Message use the plain top-level lookup, unlike status.
        val vendor = jsonOf("errorCode" to "JP_002", "errorMessage" to "Timeout")

        assertEquals("JP_002", vendor.juspayErrorCode())
        assertEquals("Timeout", vendor.juspayErrorMessage())
    }

    @Test
    fun `absent error fields read as null`() {
        val empty = jsonOf("status" to "charged")

        assertNull(empty.juspayErrorCode())
        assertNull(empty.juspayErrorMessage())
    }

    @Test
    fun `an empty vendor object does not throw`() {
        val result = juspayResultToPaymentResult(jsonObjectOf())

        assertIs<PaymentResult.Failure>(result)
    }

    // --- config decoding ------------------------------------------------------------

    @Test
    fun `fromWire returns null for an unrecognized status`() {
        assertNull(JuspayStatus.fromWire("definitely_not_a_status"))
        assertNull(JuspayStatus.fromWire(""))
    }

    @Test
    fun `status wire values are the documented lowercase strings`() {
        assertEquals("charged", JuspayStatus.CHARGED.wire)
        assertEquals("authorizing", JuspayStatus.AUTHORIZING.wire)
        assertEquals("pending_vbv", JuspayStatus.PENDING_VBV.wire)
        assertEquals("backpressed", JuspayStatus.BACKPRESSED.wire)
        assertEquals("user_aborted", JuspayStatus.USER_ABORTED.wire)
    }
}
