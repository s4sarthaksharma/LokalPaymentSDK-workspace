package com.getlokalapp.paymentsdk.model

import kotlinx.serialization.json.JsonObject

/**
 * Gateway-specific cancel codes are classified into this by the platform
 * actual (e.g. Razorpay's Android "payment cancelled" error code) before
 * reaching common code — see PaymentResult.Cancelled.
 */
enum class CancelReason {
    USER_DISMISSED,
    UNKNOWN,
}

/**
 * The unverified status a UPI app reported back through the intent result,
 * carried on [PaymentResult.Pending] for UX only. Never authoritative: a
 * [SUCCESS] hint can still resolve to a failed payment and, more dangerously,
 * a [FAILURE]/[UNKNOWN] hint can still resolve to a debited, successful one —
 * only the host's backend status check decides. [UNKNOWN] covers the common
 * case where the app returns nothing parseable (empty/null response).
 */
enum class ClientStatus {
    SUCCESS,
    FAILURE,
    UNKNOWN,
}

/**
 * Normalized error surfaced by PaymentResult.Failure, regardless of
 * whether it came from the gateway SDK, order creation, or validation.
 */
data class PaymentError(
    val code: String?,
    val message: String,
)

/**
 * Terminal state emitted on the Flow returned by each gateway module's
 * pay() (e.g. RazorpayCheckoutSdk.pay(), RazorpayCustomUiSdk.pay()).
 * Cancellation is a distinct branch from Failure by design — a user
 * dismissing the checkout sheet is not an error and should not route to
 * a failure UI.
 *
 * Success carries the raw gateway fields, not a validated outcome — the
 * SDK never calls a validate endpoint itself. The host app is expected
 * to take these straight to its own backend's validation call.
 *
 * This outcome is deliberately gateway-agnostic: which gateway produced it
 * is routing metadata owned by the entry point, not the payment result. A
 * host that pays through the generic [LokalPaymentSdk.pay] gets that gateway
 * on the [LokalPaymentResult] envelope; a host holding a specific gateway
 * module's own SDK already knows it.
 */
sealed class PaymentResult {

    data class Success(
        val paymentId: String,
        val orderId: String?,
        val signature: String,
    ) : PaymentResult()

    data class Cancelled(val reason: CancelReason) : PaymentResult()

    data class Failure(val error: PaymentError) : PaymentResult()

    /**
     * Outcome not yet known. The flow handed off to an external app — a UPI
     * intent launched into PhonePe/GPay/etc. — and control returned, but the
     * client cannot decide the result: the app's response is spoofable, often
     * empty, and a debit can succeed even when the client sees failure. Only
     * the host's backend (webhook or status poll, keyed on [txnRef]) can
     * resolve it.
     *
     * Distinct from [Success] on purpose: [Success] carries gateway fields the
     * host confirms in one shot; [Pending] carries only the correlation id and
     * obliges the host to poll-until-terminal. No non-UPI gateway emits this,
     * so its handlers' `when` branches are unreachable — but exhaustiveness
     * forces every consumer to decide how to route it.
     *
     * [clientHint] is UX flavor only (see [ClientStatus]) and must never be
     * treated as the outcome.
     */
    data class Pending(
        val txnRef: String,
        val clientHint: ClientStatus,
    ) : PaymentResult()
}

/**
 * What [com.getlokalapp.paymentsdk.LokalPaymentSdk.pay] emits: the
 * gateway-agnostic [PaymentResult] plus the [gateway] it was routed to.
 *
 * The gateway rides on this envelope rather than on [PaymentResult] because
 * it's routing metadata, not part of the payment outcome — only the generic
 * entry point knows it (the backend chose the gateway; the host didn't). A
 * host holding a specific gateway module's own SDK gets a bare
 * [PaymentResult] and already knows the gateway.
 *
 * [gateway] is non-null: the host hands [pay] a typed [PaymentOrder] whose
 * gateway is already a resolved [PaymentGateway], so there's always a gateway
 * to name — even when [result] is a [PaymentResult.Failure] because no handler
 * was registered for that gateway.
 *
 * [metadata] is whatever the host attached to [PaymentOrder.metadata], echoed
 * back verbatim (the SDK never reads or mutates it) so the host can correlate
 * this result to the originating call. `null` when the order carried none.
 */
data class LokalPaymentResult(
    val gateway: PaymentGateway,
    val result: PaymentResult,
    val metadata: JsonObject? = null,
)
