package com.getlokalapp.paymentsdk.nativeiap

import com.getlokalapp.paymentsdk.json.toJsonObject
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Vendor-neutral purchase outcome a platform client reports up to
 * [NativeIapGatewayHandler] — StoreKit 2 today (Product.purchase / Transaction.updates),
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
 * A store purchase's success payload, encoded into
 * [PaymentResult.Success.gatewayData]. There's no cryptographic signature
 * concept for a store purchase, so — unlike the Razorpay gateways — this blob
 * carries only the ids the host's backend needs to verify the purchase
 * server-side: the store `transaction_id` and the backend's own correlation
 * token (`app_account_token`), StoreKit having no gateway-issued order id.
 */
@Serializable
internal data class NativeIapSuccessResult(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("app_account_token") val appAccountToken: String?,
)

/**
 * Collapses [NativeIapPurchaseResult] into [PaymentResult]. Returns null for
 * [NativeIapPurchaseResult.Pending] — that's not a terminal outcome yet;
 * [NativeIapGatewayHandler.pay] keeps its flow open and waits for the transaction-updates
 * stream to report the eventual terminal result instead of emitting here.
 */
internal fun NativeIapPurchaseResult.toPaymentResultOrNull(): PaymentResult? = when (this) {
    is NativeIapPurchaseResult.Success -> PaymentResult.Success(
        NativeIapSuccessResult(
            transactionId = transactionId,
            appAccountToken = appAccountToken
        ).toJsonObject(),
    )

    is NativeIapPurchaseResult.Unverified -> PaymentResult.Failure(
        code = "native_iap_unverified", message = error ?: "Transaction could not be verified",
    )

    NativeIapPurchaseResult.Cancelled -> PaymentResult.Cancelled(CancelReason.USER_DISMISSED)

    NativeIapPurchaseResult.Pending -> null

    is NativeIapPurchaseResult.Failure -> PaymentResult.Failure(
        code = "native_iap_failure", message = error ?: "",
    )
}
