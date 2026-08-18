package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.paymentsdk.testkit.assertWireKeys
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Razorpay **Checkout**'s result mapping. The classification that matters is cancellation vs.
 * failure: a user dismissing the sheet is not an error and must not route a host to a failure
 * UI.
 *
 * Checkout cancels on code **0**. `:razorpay-customui` cancels on **5** and treats 0 as a real
 * failure — the two vendor flows genuinely differ, which is exactly what
 * `adding-a-new-gateway.md` rule 6 warns about ("never hardcode another gateway's code"). The
 * asymmetry is asserted from both sides, since neither module can see the other's constant.
 */
class RazorpayResultMapperTest {

    @Test
    fun `code 0 is a user cancellation rather than a failure`() {
        val result = razorpayErrorToResult(RazorpayErrorCodes.PAYMENT_CANCELLED, "Payment cancelled by user")

        assertEquals(PaymentResult.Cancelled(CancelReason.USER_DISMISSED), result)
    }

    @Test
    fun `the cancellation code for Checkout is 0`() {
        // Pinned literally: this is a vendor constant, and copying Custom UI's 5 here would
        // silently turn every cancellation into a failure.
        assertEquals(0, RazorpayErrorCodes.PAYMENT_CANCELLED)
    }

    @Test
    fun `any other code is a failure carrying the vendor code as a string`() {
        val result = razorpayErrorToResult(2, "Network error")

        assertIs<PaymentResult.Failure>(result)
        assertEquals("2", result.code)
        assertEquals("Network error", result.message)
    }

    @Test
    fun `code 5 is a failure for Checkout unlike Custom UI`() {
        // The other half of the asymmetry: 5 is Custom UI's cancel code and must stay a
        // failure here.
        assertIs<PaymentResult.Failure>(razorpayErrorToResult(5, "Some checkout error"))
    }

    @Test
    fun `a null description becomes an empty message rather than the word null`() {
        val result = razorpayErrorToResult(2, null)

        assertIs<PaymentResult.Failure>(result)
        assertEquals("", result.message)
    }

    @Test
    fun `an SDK-generated failure carries no vendor blob`() {
        val result = razorpayErrorToResult(2, "Network error")

        assertIs<PaymentResult.Failure>(result)
        // Documented: typed native errors leave gatewayData null rather than manufacturing a
        // provider payload the host might forward to its backend.
        assertEquals(null, result.gatewayData)
    }

    @Test
    fun `success carries exactly the keys Razorpay's verify API expects`() {
        val result = razorpaySuccess(paymentId = "pay_1", orderId = "order_1", signature = "sig_1")

        assertIs<PaymentResult.Success>(result)
        // These key names are the contract with the host's backend verification call.
        assertWireKeys(result.gatewayData, "razorpay_payment_id", "razorpay_order_id", "razorpay_signature")
        assertEquals("pay_1", result.gatewayData["razorpay_payment_id"]?.jsonPrimitive?.content)
        assertEquals("order_1", result.gatewayData["razorpay_order_id"]?.jsonPrimitive?.content)
        assertEquals("sig_1", result.gatewayData["razorpay_signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a null order id stays present as null instead of being dropped`() {
        val result = razorpaySuccess(paymentId = "pay_1", orderId = null, signature = "sig_1")

        assertIs<PaymentResult.Success>(result)
        assertWireKeys(result.gatewayData, "razorpay_payment_id", "razorpay_order_id", "razorpay_signature")
        assertEquals(JsonNull, result.gatewayData["razorpay_order_id"])
    }
}
