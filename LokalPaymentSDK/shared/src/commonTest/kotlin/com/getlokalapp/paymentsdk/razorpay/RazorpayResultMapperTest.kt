package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RazorpayResultMapperTest {

    @Test
    fun successCallbackMapsToSuccess() {
        val result = razorpaySuccess("pay_1", "order_1", "sig_1")

        val success = assertIs<PaymentResult.Success>(result)
        assertEquals("pay_1", success.paymentId)
        assertEquals("order_1", success.orderId)
        assertEquals("sig_1", success.signature)
    }

    @Test
    fun paymentCancelledCodeMapsToCancelled() {
        val result = razorpayErrorToResult(RazorpayErrorCodes.PAYMENT_CANCELLED, "Payment cancelled by user")

        val cancelled = assertIs<PaymentResult.Cancelled>(result)
        assertEquals(CancelReason.USER_DISMISSED, cancelled.reason)
    }

    @Test
    fun otherErrorCodeMapsToFailure() {
        val result = razorpayErrorToResult(2, "Network error")

        val failure = assertIs<PaymentResult.Failure>(result)
        assertEquals("2", failure.error.code)
        assertEquals("Network error", failure.error.message)
    }

    @Test
    fun nullDescriptionFailureUsesEmptyMessage() {
        val result = razorpayErrorToResult(2, null)

        val failure = assertIs<PaymentResult.Failure>(result)
        assertEquals("", failure.error.message)
    }
}
