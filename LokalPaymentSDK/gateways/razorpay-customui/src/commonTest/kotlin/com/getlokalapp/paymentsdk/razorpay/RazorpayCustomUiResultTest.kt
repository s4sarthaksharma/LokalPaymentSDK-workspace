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
 * Razorpay **Custom UI**'s result mapping — `Razorpay.submit()`, a different vendor callback
 * contract from `Checkout.open()`.
 *
 * The load-bearing difference: Custom UI cancels on code **5**, while `:razorpay-checkout`
 * cancels on **0**. Both modules live in the same package `com.getlokalapp.paymentsdk.razorpay`
 * and mirror each other's file layout, which makes copying between them easy and getting this
 * backwards easy too. Asserted from this side as well, since neither module can see the
 * other's constant.
 */
class RazorpayCustomUiResultTest {

    @Test
    fun `code 5 is a user cancellation rather than a failure`() {
        val result = razorpayCustomUiErrorToResult(RazorpayCustomUiErrorCodes.PAYMENT_CANCELLED, "cancelled")

        assertEquals(PaymentResult.Cancelled(CancelReason.USER_DISMISSED), result)
    }

    @Test
    fun `the cancellation code for Custom UI is 5`() {
        assertEquals(5, RazorpayCustomUiErrorCodes.PAYMENT_CANCELLED)
    }

    @Test
    fun `code 0 is a real failure here unlike Checkout`() {
        // The asymmetry that matters. If this ever returns Cancelled, someone has copied
        // :razorpay-checkout's constant into this module and every genuine error will be
        // silently reported to the host as a user cancellation.
        val result = razorpayCustomUiErrorToResult(0, "Some submit error")

        assertIs<PaymentResult.Failure>(result)
        assertEquals("0", result.code)
    }

    @Test
    fun `any other code is a failure carrying the vendor code as a string`() {
        val result = razorpayCustomUiErrorToResult(2, "Network error")

        assertIs<PaymentResult.Failure>(result)
        assertEquals("2", result.code)
        assertEquals("Network error", result.message)
    }

    @Test
    fun `a null description becomes an empty message`() {
        val result = razorpayCustomUiErrorToResult(2, null)

        assertIs<PaymentResult.Failure>(result)
        assertEquals("", result.message)
    }

    @Test
    fun `success carries exactly the keys Razorpay's verify API expects`() {
        val result = razorpayCustomUiSuccess(paymentId = "pay_1", orderId = "order_1", signature = "sig_1")

        assertIs<PaymentResult.Success>(result)
        // Same wire shape as :razorpay-checkout — the host's backend verifies both identically.
        assertWireKeys(result.gatewayData, "razorpay_payment_id", "razorpay_order_id", "razorpay_signature")
        assertEquals("pay_1", result.gatewayData["razorpay_payment_id"]?.jsonPrimitive?.content)
        assertEquals("sig_1", result.gatewayData["razorpay_signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a null order id stays present as null`() {
        val result = razorpayCustomUiSuccess(paymentId = "pay_1", orderId = null, signature = "sig_1")

        assertIs<PaymentResult.Success>(result)
        assertEquals(JsonNull, result.gatewayData["razorpay_order_id"])
    }
}
