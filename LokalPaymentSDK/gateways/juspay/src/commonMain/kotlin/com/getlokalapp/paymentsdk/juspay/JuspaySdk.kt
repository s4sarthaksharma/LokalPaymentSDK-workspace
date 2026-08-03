package com.getlokalapp.paymentsdk.juspay

import kotlinx.serialization.json.JsonObject

/**
 * The `:juspay` module's entire host-facing API: one [configure] call. Everything else about this
 * gateway — registration, readiness, the HyperSDK client, running a payment — is
 * [JuspayGatewayHandler]'s job and `internal`, reached by the SDK through
 * [com.getlokalapp.paymentsdk.LokalPaymentSdk]'s registry rather than by the host.
 *
 * Kept as a separate public object rather than making the handler itself public for two reasons:
 * hosts shouldn't have to know the SDK models gateways as handlers, and a public handler would drag
 * [JuspayConfig] — a backend wire format — into the public API, since
 * [com.getlokalapp.paymentsdk.TypedPaymentGatewayHandler] puts its config type in member
 * signatures.
 *
 * This object holds no state and needs no startup trigger; the handler's registering `init` block
 * is what `JuspayInitializer` (Android) and `JuspayEagerInit.kt` (iOS) run at process start.
 */
object JuspaySdk {

    /**
     * Initiates HyperSDK with [initPayload]. Required before a Juspay payment can run —
     * registration alone only means the module is present — and safe to call again, e.g. on
     * resume or when the payload changes, to refresh HyperSDK's cached init payload. Call it
     * once at app startup; the same call serves both platforms. Until it has run,
     * `LokalPaymentSdk.gatewayStatus()` reports Juspay as
     * [com.getlokalapp.paymentsdk.model.GatewayReadiness.NotReady] and a payment fails
     * gracefully with `juspay_not_initialized` rather than crashing.
     *
     * No clientId parameter — neither platform actual takes a host-supplied one. Android reads it
     * implicitly from the host's Gradle-plugin-injected config; iOS resolves it itself at runtime
     * from the bundled `MerchantConfig.json` (see `IOSJuspayClient`). [tenantId] defaults to
     * Juspay India's shared tenant, correct for every host we have, and is honored from the first
     * call only. No onBackPressed forwarding on either platform — confirmed dead code even in
     * matrimony's own shipped implementation.
     */
    fun configure(
        initPayload: JsonObject,
        tenantId: String = "juspayindia",
    ) {
        JuspayGatewayHandler.configure(initPayload, tenantId)
    }
}
