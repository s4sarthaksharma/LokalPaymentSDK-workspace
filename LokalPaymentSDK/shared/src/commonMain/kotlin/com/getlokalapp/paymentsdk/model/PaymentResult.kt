package com.getlokalapp.paymentsdk.model

import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
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
 * What a gateway module's [com.getlokalapp.paymentsdk.PaymentGatewayHandler.pay] Flow emits:
 * either a non-terminal [GatewayUi] lifecycle signal, or a terminal [PaymentResult] — which is
 * itself a `PaymentGatewayEvent`, so a gateway emits the result directly with no wrapper.
 *
 * [GatewayUi] is non-terminal lifecycle information about the gateway's own UI. [GatewayUi.Presented]
 * arrives at most once, always before the terminal [PaymentResult], and means "the gateway has taken
 * over the screen now (a full-screen sheet, an in-place Fragment/WebView, ...) - it's safe to drop
 * whatever 'please wait' UI you were showing since the call to pay()." Most gateways take over as
 * good as immediately, so [com.getlokalapp.paymentsdk.LokalPaymentSdk.pay] emits their Presented at
 * flow start on their behalf; a gateway that needs slow work *before* its UI can appear declares
 * [com.getlokalapp.paymentsdk.model.GatewayCapability.SELF_REPORTS_UI] and emits its own at the right
 * moment instead. The matching
 * [GatewayUi.Dismissed] is synthesized by [com.getlokalapp.paymentsdk.LokalPaymentSdk.pay] itself,
 * right before the terminal, whenever a [GatewayUi.Presented] was emitted - so a host always sees
 * the two as a matched pair (or neither), never a lone Presented.
 *
 * The terminal [PaymentResult] is the one required emission - exactly once, ending the flow.
 */
sealed interface PaymentGatewayEvent {

    /**
     * The gateway's own UI lifecycle - a non-terminal, paired signal every gateway produces.
     * [Presented] comes either from the SDK at flow start or, for a
     * [GatewayCapability.SELF_REPORTS_UI] gateway, from the gateway itself at the precise moment;
     * [com.getlokalapp.paymentsdk.LokalPaymentSdk.pay] then guarantees a
     * matching [Dismissed] right before the terminal [PaymentResult], so a host that reacts to
     * [Presented] (pausing a video, hiding a loader, ...) can cleanly undo it on [Dismissed]
     * without having to treat "any terminal" as the dismissal.
     */
    sealed interface GatewayUi : PaymentGatewayEvent {
        data object Presented : GatewayUi
        data object Dismissed : GatewayUi
    }

    /**
     * Terminal state emitted on the Flow returned by each gateway module's
     * pay() (e.g. RazorpayCheckoutGatewayHandler.pay(), RazorpayCustomUiGatewayHandler.pay()).
     * Cancellation is a distinct branch from Failure by design — a user
     * dismissing the checkout sheet is not an error and should not route to
     * a failure UI.
     *
     * Success carries an opaque, gateway-specific blob ([Success.gatewayData]),
     * not a validated outcome — the SDK never calls a validate endpoint itself.
     * The host forwards that blob straight to its own backend's validation call.
     *
     * Each `PaymentResult` **is** a terminal [PaymentGatewayEvent] (the only other
     * events are the non-terminal [PaymentGatewayEvent.GatewayUi] signals),
     * so a gateway's `pay()` emits a result directly — e.g.
     * `trySend(PaymentResult.Failure(code, message))` — with no wrapper.
     *
     * This outcome is deliberately gateway-agnostic: which gateway produced it is
     * routing metadata owned by the entry point, not the payment result — it rides
     * on the [LokalPaymentEvent] envelope that
     * [com.getlokalapp.paymentsdk.LokalPaymentSdk.pay] emits, not on the result. A
     * host holding a specific gateway module's own SDK already knows the gateway.
     */
    sealed interface PaymentResult : PaymentGatewayEvent {

        data class Success(val gatewayData: JsonObject) : PaymentResult

        data class Cancelled(val reason: CancelReason) : PaymentResult

        data class Failure(val code: String?, val message: String) : PaymentResult

        /**
         * Outcome not yet known. The flow handed off to an external app — a UPI
         * intent launched into PhonePe/GPay/etc. — and control returned, but the
         * client cannot decide the result: the app's response is spoofable, often
         * empty, and a debit can succeed even when the client sees failure. Only
         * the host's backend (webhook or status poll, keyed on the gateway's own
         * transaction reference inside [Pending.gatewayData]) can resolve it.
         *
         * Distinct from [Success] on purpose: both carry only an opaque
         * gateway-specific blob the host forwards to its backend, but [Pending]
         * obliges the host to poll-until-terminal rather than confirm in one shot.
         * No non-UPI gateway emits this, so its handlers' `when` branches are
         * unreachable — but exhaustiveness forces every consumer to decide how to
         * route it.
         */
        data class Pending(val gatewayData: JsonObject) : PaymentResult
    }
}

/**
 * What [com.getlokalapp.paymentsdk.LokalPaymentSdk.paymentEvents] emits: a gateway's own
 * [PaymentGatewayEvent] wrapped with the routing context only the generic entry
 * point knows.
 *
 * [event] is the gateway's event — a non-terminal [PaymentGatewayEvent.GatewayUi] signal,
 * or a terminal [PaymentResult]. [gateway] is which gateway the backend routed
 * to; it rides here rather than on [PaymentResult] because it's routing metadata,
 * not part of the outcome, and it's always known (the host handed `pay` a typed
 * [PaymentOrder]). [metadata] is whatever the host attached to
 * [PaymentOrder.metadata], echoed back verbatim (the SDK never reads or mutates
 * it) so the host can correlate this event to the originating call — `null` when
 * the order carried none.
 */
data class LokalPaymentEvent(
    val gateway: PaymentGateway,
    val event: PaymentGatewayEvent,
    val metadata: JsonObject? = null,
)

/**
 * Summary of a [PaymentResult] for logs. [PaymentResult.Success] and
 * [PaymentResult.Pending] dump their full [PaymentResult.Success.gatewayData]
 * blob — including any gateway-specific ids or signatures it carries — so a
 * log sink receives verbatim whatever the gateway returned.
 */
fun PaymentResult.describeForLog(): String = when (this) {
    is PaymentResult.Success -> "Success(gatewayData=$gatewayData)"
    is PaymentResult.Cancelled -> "Cancelled(reason=$reason)"
    is PaymentResult.Failure -> "Failure(code=$code, message=$message)"
    is PaymentResult.Pending -> "Pending(gatewayData=$gatewayData)"
}
