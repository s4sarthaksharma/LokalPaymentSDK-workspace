package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.AvailableGateway
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.GatewayReadiness
import com.getlokalapp.paymentsdk.model.GatewayStatusReport
import com.getlokalapp.paymentsdk.model.LokalPaymentResult
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentOrder
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.model.UnavailableGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Entry point for the shared Lokal Payment SDK.
 *
 * The host calls its own backend to create an order, decodes that response
 * into a [PaymentOrder], then hands it to [pay] — always the same call,
 * regardless of which gateway the backend picked. [pay] reads
 * `PaymentOrder.gateway`
 * and routes to whichever [PaymentGatewayHandler] is currently registered
 * for it. There's no list to build: each gateway module's singleton handler
 * registers itself at app startup (see [PaymentGatewayHandler]'s kdoc for
 * the per-platform bootstrap), so LokalPaymentSdk only ever knows about the
 * gateway modules the host actually included. Registration is app-lifetime;
 * there is no unregister.
 */
object LokalPaymentSdk {

    private val handlers = mutableMapOf<PaymentGateway, PaymentGatewayHandler>()
    private val unavailable = mutableMapOf<PaymentGateway, UnavailableGateway>()

    /**
     * Called from a gateway singleton's own `init` block — not something a
     * host normally calls directly. Handlers are singletons, so calling it
     * again for the same gateway is a harmless no-op. Every gateway module
     * registers at startup with zero host code (Android: an AndroidX App
     * Startup `Initializer` run synchronously before `Application.onCreate()`;
     * iOS: `@EagerInitialization`), so this always runs before any host code,
     * regardless of whether the gateway needs further host-supplied setup
     * (e.g. Juspay's `initialize(...)`) before [pay] actually works.
     */
    fun register(handler: PaymentGatewayHandler) {
        handlers[handler.gateway] = handler
    }

    /**
     * Called from a gateway module's own eager startup hook when that
     * module is compiled into the build but structurally can't work on this
     * platform (e.g. Razorpay UPI Intent on iOS) — not something a host
     * normally calls directly. [metadata] is still the calling module's own
     * build info, same as a registered handler's [PaymentGatewayHandler.metadata].
     */
    fun registerUnavailable(gateway: PaymentGateway, reasonCode: String, reasonMessage: String, metadata: GatewayMetadata) {
        unavailable[gateway] = UnavailableGateway(gateway, reasonCode, reasonMessage, metadata)
    }

    /**
     * Snapshot of every gateway a host can query at runtime: [available]
     * lists gateways that are registered and report themselves ready to pay
     * right now (see [PaymentGatewayHandler.readiness]), with each gateway's
     * own build metadata; [unavailable] lists both gateways whose module is
     * compiled into this build but structurally can't work here (e.g.
     * Razorpay UPI Intent on iOS) and gateways that are registered but not
     * ready yet (e.g. Juspay before its `initialize()` call) — either way,
     * with why. A gateway module the host never included appears in neither.
     * Call this fresh whenever readiness matters — e.g. right after calling
     * a gateway's `initialize()` — rather than caching one snapshot, since a
     * handler's readiness can change during the app's lifetime.
     */
    fun gatewayStatus(): GatewayStatusReport {
        val available = mutableListOf<AvailableGateway>()
        val notReady = mutableListOf<UnavailableGateway>()
        for (handler in handlers.values) {
            when (val readiness = handler.readiness()) {
                is GatewayReadiness.Ready ->
                    available += AvailableGateway(handler.gateway, handler.metadata)
                is GatewayReadiness.NotReady ->
                    notReady += UnavailableGateway(handler.gateway, readiness.reasonCode, readiness.reasonMessage, handler.metadata)
            }
        }
        return GatewayStatusReport(
            paymentSdkVersion = VERSION,
            available = available,
            unavailable = unavailable.values.toList() + notReady,
        )
    }

    /**
     * Runs a payment for the given order and emits exactly one terminal
     * [LokalPaymentResult] — the gateway-agnostic [PaymentResult] plus the
     * resolved gateway — before completing.
     *
     * @param order the host's create-order response, already decoded into a [PaymentOrder]
     */
    fun pay(order: PaymentOrder): Flow<LokalPaymentResult> {
        val handler = handlers[order.gateway]
            ?: return flowOf(
                LokalPaymentResult(
                    gateway = order.gateway,
                    result = PaymentResult.Failure(unavailableError(order.gateway)),
                ),
            )
        return handler.pay(order.gatewayConfig).map { LokalPaymentResult(order.gateway, it) }
    }

    private fun unavailableError(gateway: PaymentGateway): PaymentError {
        val reason = unavailable[gateway]
        return if (reason != null) {
            PaymentError(code = reason.reasonCode, message = reason.reasonMessage)
        } else {
            PaymentError(
                code = "unsupported_gateway",
                message = "No PaymentGatewayHandler registered for gateway " +
                    "$gateway. Did you forget to include that gateway's module, " +
                    "or to call its initialize function if it has one?",
            )
        }
    }

    const val VERSION: String = PAYMENT_SDK_VERSION
}
