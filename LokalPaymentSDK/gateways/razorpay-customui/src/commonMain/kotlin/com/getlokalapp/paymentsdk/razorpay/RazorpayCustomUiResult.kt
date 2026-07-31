package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.json.toJsonObject
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Razorpay's Custom UI (Razorpay.submit()) error codes, as passed to
 * [RazorpayCustomUiResultListener.onPaymentError]. Verified against
 * matrimony-kmp's production Razorpay submit() (UPI Intent) integration — note
 * this differs from `:razorpay-checkout`'s cancellation code (0).
 */
internal object RazorpayCustomUiErrorCodes {
    const val PAYMENT_CANCELLED = 5
}

/**
 * Raw callback forwarded as-is from the platform Razorpay SDK's
 * Razorpay.submit(). Not shared with `:razorpay-checkout`'s listener —
 * submit()'s callback contract (and its cancellation code) differs from
 * Checkout.open()'s. Classifying `code` into cancellation vs. a real failure
 * happens one layer down, below.
 */
internal interface RazorpayCustomUiResultListener {
    fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String)
    fun onPaymentError(code: Int, description: String?)
}

/**
 * Razorpay Custom UI's success payload, encoded into
 * [PaymentResult.Success.gatewayData] under the keys Razorpay's own verify API
 * uses. Mirrors `:razorpay-checkout`'s RazorpayCheckoutResult — the host
 * forwards this blob straight to its backend's signature-verification call.
 */
@Serializable
internal data class RazorpayCustomUiResult(
    @SerialName("razorpay_payment_id") val paymentId: String,
    @SerialName("razorpay_order_id") val orderId: String?,
    @SerialName("razorpay_signature") val signature: String,
)

/**
 * Normalizes the raw Razorpay Custom UI (submit()) listener callbacks into a
 * PaymentResult. Mirrors `:razorpay-checkout`'s RazorpayResultMapper, but
 * against this flow's own cancellation code.
 */
internal fun razorpayCustomUiSuccess(paymentId: String, orderId: String?, signature: String): PaymentResult =
    PaymentResult.Success(
        RazorpayCustomUiResult(
            paymentId = paymentId,
            orderId = orderId,
            signature = signature
        ).toJsonObject(),
    )

internal fun razorpayCustomUiErrorToResult(code: Int, description: String?): PaymentResult =
    if (code == RazorpayCustomUiErrorCodes.PAYMENT_CANCELLED) {
        PaymentResult.Cancelled(CancelReason.USER_DISMISSED)
    } else {
        PaymentResult.Failure(code = code.toString(), message = description ?: "")
    }
