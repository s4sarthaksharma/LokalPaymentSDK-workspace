package com.getlokalapp.paymentsdk.nativeiap

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentResult

/**
 * Vendor-neutral purchase outcome a platform client reports up to
 * [NativeIapSdk] — StoreKit 2 today (Product.purchase / Transaction.updates),
 * Play Billing later. Richer than [PaymentResult]: a store purchase can be
 * unverified (StoreKit's local receipt check failed) or still pending (e.g.
 * Ask to Buy, SCA) — neither of which [PaymentResult] models.
 */
internal sealed interface NativeIapPurchaseResult {

    data class Success(
        val productId: String,
        val transactionId: String,
        val appAccountToken: String?,
    ) : NativeIapPurchaseResult

    data class Unverified(val transactionId: String, val error: String?) : NativeIapPurchaseResult
    data object Cancelled : NativeIapPurchaseResult
    data object Pending : NativeIapPurchaseResult
    data class Failure(val error: String?) : NativeIapPurchaseResult
}

/**
 * Collapses [NativeIapPurchaseResult] into [PaymentResult]. Returns null for
 * [NativeIapPurchaseResult.Pending] — that's not a terminal outcome yet;
 * [NativeIapSdk.pay] keeps its flow open and waits for the transaction-updates
 * stream to report the eventual terminal result instead of emitting here.
 *
 * [PaymentResult.Success.signature] is always empty — there's no cryptographic
 * signature concept for a store purchase, matching how Juspay's own mapper
 * leaves it blank. [orderId] carries the backend's own correlation token
 * ([NativeIapPurchaseResult.Success.appAccountToken]) rather than a
 * gateway-issued order id, since StoreKit has no such concept either.
 */
internal fun NativeIapPurchaseResult.toPaymentResultOrNull(): PaymentResult? = when (this) {
    is NativeIapPurchaseResult.Success -> PaymentResult.Success(
        paymentId = transactionId,
        orderId = appAccountToken,
        signature = "",
    )

    is NativeIapPurchaseResult.Unverified -> PaymentResult.Failure(
        PaymentError(code = "native_iap_unverified", message = error ?: "Transaction could not be verified"),
    )

    NativeIapPurchaseResult.Cancelled -> PaymentResult.Cancelled(CancelReason.USER_DISMISSED)

    NativeIapPurchaseResult.Pending -> null

    is NativeIapPurchaseResult.Failure -> PaymentResult.Failure(
        PaymentError(code = "native_iap_failure", message = error ?: ""),
    )
}
