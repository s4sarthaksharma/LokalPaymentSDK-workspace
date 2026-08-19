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

    /**
     * The gateway's UI went away before it could report anything, so no result will ever arrive -
     * the system destroyed the Activity/ViewController driving the payment (backgrounded during a
     * UPI hand-off and reclaimed under memory pressure, "don't keep activities", ...) and the
     * gateway delivers its result only to that exact instance.
     *
     * Distinct from [USER_DISMISSED] because the user did not decline anything: they may well have
     * completed the payment in the app the gateway handed off to, so a host should treat this as
     * "outcome unknown, reconcile with the backend" rather than showing a cancelled or failed
     * payment. What it is *not* is a reason to keep waiting - this reason exists precisely because
     * the SDK can prove no terminal result is coming, and a host that stays pending here would
     * block the user out of paying at all.
     */
    UI_DESTROYED,
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
     * Success and Pending carry opaque gateway-specific blobs; Failure carries
     * one when the gateway supplied raw result data. These are not validated
     * outcomes — the SDK never calls a validate endpoint itself. The host
     * forwards available gateway data to its own backend.
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

        /**
         * A failed attempt. [gatewayData] preserves the gateway's complete raw
         * result object when one exists; SDK-generated and typed native errors
         * leave it `null` rather than manufacturing a provider payload.
         */
        data class Failure(
            val code: String?,
            val message: String,
            val gatewayData: JsonObject? = null,
        ) : PaymentResult

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
         * UPI Intent, Juspay and Web Checkout can emit this; exhaustiveness
         * forces every consumer to decide how to route an unresolved outcome.
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
    /** SDK-generated UUID string identifying this specific pay() attempt. */
    val operationId: String,
    val gateway: PaymentGateway,
    val event: PaymentGatewayEvent,
    val metadata: JsonObject? = null,
)

/**
 * Summary of a [PaymentResult] for logs. Success, Pending and gateway-originated
 * Failure dump their complete gateway blob — including any gateway-specific
 * ids or signatures it carries — so a configured debug log sink receives
 * verbatim whatever the gateway returned.
 */
fun PaymentResult.describeForLog(): String = when (this) {
    is PaymentResult.Success -> "Success(gatewayData=$gatewayData)"
    is PaymentResult.Cancelled -> "Cancelled(reason=$reason)"
    is PaymentResult.Failure -> "Failure(code=$code, message=$message, gatewayData=$gatewayData)"
    is PaymentResult.Pending -> "Pending(gatewayData=$gatewayData)"
}
