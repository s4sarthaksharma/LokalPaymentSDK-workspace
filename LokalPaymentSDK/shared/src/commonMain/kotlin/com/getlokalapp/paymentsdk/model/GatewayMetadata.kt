package com.getlokalapp.paymentsdk.model

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Per-gateway build info a host can use to spot version skew (e.g. a gateway
 * module the host forgot to bump alongside `:shared`). [moduleVersion] is
 * that gateway module's own published LokalPaymentSDK version — compare it
 * against [GatewayStatusReport.paymentSdkVersion]. [vendorSdkVersion] is the
 * underlying gateway SDK's version (Razorpay AAR/pod, HyperSDK, ...), which
 * can differ by platform for the same gateway. [extras] carries anything
 * else gateway-specific worth surfacing (e.g. environment); empty by default.
 */
@Serializable
data class GatewayMetadata(
    val moduleVersion: String,
    val vendorSdkVersion: String,
    val extras: Map<String, String> = emptyMap(),
)

/** One entry of [GatewayStatusReport.available]. */
@Serializable
data class AvailableGateway(
    val gateway: PaymentGateway,
    val metadata: GatewayMetadata,
)

/**
 * One entry of [GatewayStatusReport.unavailable] — either a gateway whose
 * module is compiled in but structurally can't work on this platform (e.g.
 * Razorpay Custom UI on iOS), or one that's registered but not
 * [GatewayReadiness.Ready] yet (e.g. Juspay before its `initialize()` call).
 * [reasonCode] is stable and machine-checkable (e.g. "unsupported_platform",
 * "juspay_not_initialized"); [reasonMessage] is the human-readable form for
 * logs/diagnostics UI. [metadata] is still this gateway module's own build
 * info — useful for spotting version skew even on a gateway that can't pay
 * right now.
 */
@Serializable
data class UnavailableGateway(
    val gateway: PaymentGateway,
    val reasonCode: String,
    val reasonMessage: String,
    val metadata: GatewayMetadata,
)

/**
 * Snapshot of every gateway a host can query at runtime — see
 * [com.getlokalapp.paymentsdk.LokalPaymentSdk.gatewayStatus]. A gateway
 * module the host never included doesn't appear in either list. This is a
 * live readiness check, not a static build manifest — re-query it whenever
 * up-to-date readiness matters (e.g. right after calling a gateway's
 * `initialize()`) rather than caching one snapshot.
 */
@Serializable
data class GatewayStatusReport(
    val paymentSdkVersion: String,
    val available: List<AvailableGateway>,
    val unavailable: List<UnavailableGateway>,
) {
    /**
     * Serializes the whole report — every gateway, its metadata and (for
     * unavailable ones) the reason — to a JSON string, handy for logging or
     * shipping to diagnostics. [PaymentGateway] values encode as their
     * [PaymentGateway.code] (e.g. `"juspay"`).
     */
    fun toJson(): String = lenientJson.encodeToString(this)
}

/**
 * A [com.getlokalapp.paymentsdk.PaymentGatewayHandler]'s own answer to "can
 * you actually process a payment right now?" — checked live on every
 * [com.getlokalapp.paymentsdk.LokalPaymentSdk.gatewayStatus] call, unlike
 * [UnavailableGateway] registration (structural, decided once at process
 * start for platforms the handler never exists on at all). [NotReady] is for
 * a handler that exists but still needs host-supplied setup, e.g. Juspay
 * before its `initialize()` call.
 */
sealed interface GatewayReadiness {
    data object Ready : GatewayReadiness
    data class NotReady(val reasonCode: String, val reasonMessage: String) : GatewayReadiness
}

/**
 * A static, declared-once capability of a payment gateway. The SDK reads a handler's
 * [com.getlokalapp.paymentsdk.PaymentGatewayHandler.capabilities] to drive gateway-agnostic
 * behavior; a gateway that declares none falls back to the SDK's default handling. Modeled as a
 * `Set` so it grows as new cross-gateway behaviors appear.
 */
enum class GatewayCapability {
    /**
     * The gateway renders its UI in-place inside the host's own view (Juspay's HyperSDK Fragment)
     * and reports the [PaymentGatewayEvent.GatewayUi.Presented] moment itself, precisely. For a
     * gateway *without* this capability the SDK brackets the [PaymentGatewayEvent.GatewayUi]
     * lifecycle by default — emitting [PaymentGatewayEvent.GatewayUi.Presented] at flow start and
     * [PaymentGatewayEvent.GatewayUi.Dismissed] before the terminal — so every gateway produces the
     * pair. A self-reporting gateway emits its own precise Presented and the SDK only pairs the
     * Dismissed.
     */
    SELF_REPORTS_UI,
}
