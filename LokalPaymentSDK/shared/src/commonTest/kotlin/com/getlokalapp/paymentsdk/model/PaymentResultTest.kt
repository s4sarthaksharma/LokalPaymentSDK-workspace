package com.getlokalapp.paymentsdk.model

import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.paymentsdk.testkit.jsonOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [describeForLog] is what a host's configured log sink receives for a terminal result, and
 * `IMPROVEMENT_02` accepts by design that it dumps the complete gateway blob — the host owns
 * the logger and installs it in debug builds only. These tests pin that intent so a later
 * "let's redact this" change has to be deliberate, and pin that the discriminating field of
 * each subtype is present (a log line that cannot tell Cancelled from Failure is useless).
 */
class PaymentResultTest {

    @Test
    fun `describes success with its full gateway blob`() {
        val result = PaymentResult.Success(jsonOf("razorpay_payment_id" to "pay_1", "razorpay_signature" to "sig_1"))

        val described = result.describeForLog()

        assertTrue(described.startsWith("Success("), described)
        // Deliberate per IMPROVEMENT_02: the whole payload is diagnosable, including the
        // signature, because a mapping bug is otherwise invisible at the SDK boundary.
        assertTrue("pay_1" in described, described)
        assertTrue("sig_1" in described, described)
    }

    @Test
    fun `describes a cancellation by reason`() {
        val described = PaymentResult.Cancelled(CancelReason.USER_DISMISSED).describeForLog()

        assertEquals("Cancelled(reason=USER_DISMISSED)", described)
    }

    @Test
    fun `describes each cancel reason distinctly`() {
        CancelReason.entries.forEach { reason ->
            assertTrue(
                reason.name in PaymentResult.Cancelled(reason).describeForLog(),
                "reason=$reason was not identifiable in the log line",
            )
        }
    }

    @Test
    fun `describes failure with code and message and blob`() {
        val result = PaymentResult.Failure(
            code = "bad_gateway_config",
            message = "Unparseable gateway_config",
            gatewayData = jsonOf("raw" to "vendor detail"),
        )

        val described = result.describeForLog()

        assertTrue("bad_gateway_config" in described, described)
        assertTrue("Unparseable gateway_config" in described, described)
        assertTrue("vendor detail" in described, described)
    }

    @Test
    fun `describes a failure that carries no blob`() {
        // SDK-generated failures leave gatewayData null rather than manufacturing a payload.
        val described = PaymentResult.Failure(code = "unsupported_gateway", message = "no handler").describeForLog()

        assertTrue("gatewayData=null" in described, described)
    }

    @Test
    fun `describes pending with its blob`() {
        val result = PaymentResult.Pending(jsonOf("txn_ref" to "TXN1", "client_hint" to "UNKNOWN"))

        val described = result.describeForLog()

        assertTrue(described.startsWith("Pending("), described)
        assertTrue("TXN1" in described, described)
    }

    @Test
    fun `each subtype is distinguishable in a log line`() {
        val blob = jsonOf("k" to "v")
        val described = listOf(
            PaymentResult.Success(blob),
            PaymentResult.Cancelled(CancelReason.UNKNOWN),
            PaymentResult.Failure("code", "message"),
            PaymentResult.Pending(blob),
        ).map { it.describeForLog().substringBefore("(") }

        assertEquals(listOf("Success", "Cancelled", "Failure", "Pending"), described)
    }

    @Test
    fun `failure gatewayData defaults to null`() {
        assertNull(PaymentResult.Failure(code = "c", message = "m").gatewayData)
    }

    @Test
    fun `a payment result is itself a gateway event`() {
        // The type relationship a gateway relies on: it emits a result directly, no wrapper.
        val result: PaymentGatewayEvent = PaymentResult.Cancelled(CancelReason.USER_DISMISSED)

        assertTrue(result is PaymentResult)
    }

    @Test
    fun `event metadata defaults to null and is echoed verbatim`() {
        val metadata = jsonOf("order_ref" to "host-123")

        val withMetadata = LokalPaymentEvent(
            operationId = "op-1",
            gateway = PaymentGateway.JUSPAY,
            event = PaymentResult.Cancelled(CancelReason.UNKNOWN),
            metadata = metadata,
        )
        val without = LokalPaymentEvent(
            operationId = "op-2",
            gateway = PaymentGateway.JUSPAY,
            event = PaymentResult.Cancelled(CancelReason.UNKNOWN),
        )

        // Host-owned passthrough: the SDK must neither read nor reshape it.
        assertEquals(metadata, withMetadata.metadata)
        assertNull(without.metadata)
    }
}
