package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.json.toJsonObject
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Razorpay's own checkout error codes, as passed to
 * RazorpayPaymentResultListener.onPaymentError. Verified against
 * matrimony-kmp's production Razorpay Checkout integration.
 */
internal object RazorpayErrorCodes {
    const val PAYMENT_CANCELLED = 0
}

/**
 * Razorpay Checkout's success payload, encoded into
 * [PaymentResult.Success.gatewayData] under the keys Razorpay's own verify API
 * uses — the host forwards this blob straight to its backend's
 * signature-verification call, so the SDK itself never inspects it.
 */
@Serializable
internal data class RazorpayCheckoutResult(
    @SerialName("razorpay_payment_id") val paymentId: String,
    @SerialName("razorpay_order_id") val orderId: String?,
    @SerialName("razorpay_signature") val signature: String,
)

/**
 * Normalizes the raw Razorpay listener callbacks into a PaymentResult.
 * This is the "one layer up" classification that RazorpayPaymentResultListener's
 * doc deliberately keeps out of the platform clients — a user dismissing the
 * sheet (PAYMENT_CANCELLED) is a Cancelled, not a Failure.
 */
internal fun razorpaySuccess(paymentId: String, orderId: String?, signature: String): PaymentResult =
    PaymentResult.Success(
        RazorpayCheckoutResult(
            paymentId = paymentId,
            orderId = orderId,
            signature = signature
        ).toJsonObject(),
    )

/**
 * The checkout UI died before Razorpay reported anything. Cancelled rather than Failure: nothing
 * failed, we simply cannot know the outcome - see [CancelReason.UI_DESTROYED] for why the host must
 * reconcile instead of trusting this as a decline.
 */
internal fun razorpayUiDestroyed(): PaymentResult = PaymentResult.Cancelled(CancelReason.UI_DESTROYED)

internal fun razorpayErrorToResult(code: Int, description: String?): PaymentResult =
    if (code == RazorpayErrorCodes.PAYMENT_CANCELLED) {
        PaymentResult.Cancelled(CancelReason.USER_DISMISSED)
    } else {
        PaymentResult.Failure(code = code.toString(), message = description ?: "")
    }
