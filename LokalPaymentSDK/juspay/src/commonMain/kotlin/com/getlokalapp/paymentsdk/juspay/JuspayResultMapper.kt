package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentResult

/** Raw, already-extracted fields from a Juspay process_result event. */
internal data class JuspayResultData(
    val status: String,
    val orderId: String?,
    val txnId: String?,
    val errorCode: String?,
    val errorMessage: String?,
)

/**
 * Classifies Juspay's own statuses into a PaymentResult (D6/D7) — the "one
 * layer up" judgment kept out of the platform clients, mirroring
 * RazorpayResultMapper.
 */
internal fun juspayResultToPaymentResult(data: JuspayResultData): PaymentResult =
    when (data.status.lowercase()) {
        JuspayStatus.CHARGED, JuspayStatus.AUTHORIZING, JuspayStatus.PENDING_VBV ->
            PaymentResult.Success(
                paymentId = data.txnId.orEmpty(),
                orderId = data.orderId,
                signature = "",
            )

        JuspayStatus.BACKPRESSED, JuspayStatus.USER_ABORTED ->
            PaymentResult.Cancelled(CancelReason.USER_DISMISSED)

        else ->
            PaymentResult.Failure(
                PaymentError(
                    code = data.errorCode ?: data.status,
                    message = data.errorMessage ?: "Juspay payment failed (status=${data.status})",
                ),
            )
    }
