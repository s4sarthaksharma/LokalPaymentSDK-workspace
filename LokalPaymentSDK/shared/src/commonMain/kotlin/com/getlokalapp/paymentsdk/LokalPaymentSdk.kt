package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.AvailableGateway
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.GatewayReadiness
import com.getlokalapp.paymentsdk.model.GatewayStatusReport
import com.getlokalapp.paymentsdk.model.LokalPaymentGatewayEvent
import com.getlokalapp.paymentsdk.model.LokalPaymentResult
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import com.getlokalapp.paymentsdk.model.PaymentOrder
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.model.UnavailableGateway
import com.getlokalapp.paymentsdk.upi.UpiApp
import com.getlokalapp.paymentsdk.upi.detectInstalledUpiApps
import com.getlokalapp.util.LokalLogger
import com.getlokalapp.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex

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
     * Global single-flight guard: at most one [pay] flow may be collecting at
     * a time, across every gateway. A second [pay] collection that starts
     * while one is already in flight is rejected immediately with a terminal
     * [PaymentResult.Failure] carrying the [ALREADY_IN_PROGRESS] code, rather
     * than launching a second checkout. This is the SDK-level safety net
     * beneath any host-side double-tap debouncing — it stops two proxy
     * Activities / checkout sheets from ever opening at once.
     */
    private val inFlight = Mutex()

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
     * platform (e.g. Razorpay Custom UI on iOS) — not something a host
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
     * Razorpay Custom UI on iOS) and gateways that are registered but not
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
     * Runs a payment for the given order and emits [LokalPaymentGatewayEvent]s: an optional
     * [LokalPaymentGatewayEvent.UiPresented] (see [PaymentGatewayEvent.UiPresented] for what it
     * means and which gateways emit it), then exactly one terminal
     * [LokalPaymentGatewayEvent.Terminal] wrapping the gateway-agnostic [PaymentResult] plus the
     * resolved gateway, before completing.
     *
     * @param order the host's create-order response, already decoded into a [PaymentOrder]
     */
    fun pay(order: PaymentOrder): Flow<LokalPaymentGatewayEvent> {
        val handler = handlers[order.gateway]
            ?: return flowOf(
                LokalPaymentGatewayEvent.Terminal(
                    LokalPaymentResult(
                        gateway = order.gateway,
                        result = PaymentResult.Failure(unavailableError(order.gateway)),
                        metadata = order.metadata,
                    ),
                ),
            )
        return flow {
            if (!inFlight.tryLock()) {
                emit(
                    LokalPaymentGatewayEvent.Terminal(
                        LokalPaymentResult(
                            gateway = order.gateway,
                            result = PaymentResult.Failure(alreadyInProgressError()),
                            metadata = order.metadata,
                        ),
                    ),
                )
                return@flow
            }
            try {
                emitAll(
                    handler.pay(order.gatewayConfig).map { event ->
                        when (event) {
                            PaymentGatewayEvent.UiPresented -> LokalPaymentGatewayEvent.UiPresented(order.gateway)
                            is PaymentGatewayEvent.Terminal ->
                                LokalPaymentGatewayEvent.Terminal(LokalPaymentResult(order.gateway, event.result, order.metadata))
                        }
                    },
                )
            } finally {
                inFlight.unlock()
            }
        }
    }

    private fun alreadyInProgressError(): PaymentError =
        PaymentError(
            code = ALREADY_IN_PROGRESS,
            message = "A payment is already in progress; ignoring this concurrent pay() call.",
        )

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

    /**
     * Lists the UPI payment apps currently installed on the device, for a host
     * that wants to show the user a chooser (or decide whether a UPI flow is
     * even worth offering) before creating an order.
     *
     * Results are platform-shaped — see [UpiApp]. **Android** returns every app
     * that can handle `upi://pay`, with real [UpiApp.packageName]s. **iOS**
     * returns matches from a curated URL-scheme catalog; note that iOS's
     * `canOpenURL` only reports an app as present if its scheme is declared in
     * the **host app's** `Info.plist` under `LSApplicationQueriesSchemes`, so an
     * iOS host must list the UPI schemes it cares about there or this returns
     * an empty list.
     *
     * Returns an empty list if detection isn't possible (e.g. the Android SDK
     * hasn't been initialized yet, so no [android.content.Context] is available).
     */
    fun installedUpiApps(): List<UpiApp> = detectInstalledUpiApps()

    /**
     * Supplies a [LokalLogger] to receive structured logs emitted by SDK gateways as they
     * run. Pass `null` to stop receiving logs. Safe to call at any point in the app's
     * lifetime — logging before this is called (e.g. during a gateway's own eager startup)
     * is silently skipped rather than buffered.
     */
    fun setLogger(logger: LokalLogger) {
        Log = logger
    }

    const val VERSION: String = PAYMENT_SDK_VERSION

    /**
     * Stable [PaymentError.code] on the terminal [PaymentResult.Failure] emitted
     * when a [pay] call is rejected because another payment is already in flight
     * (see [inFlight]). Hosts can match on this to silently ignore a double-tap
     * rather than surfacing it as a real failure.
     */
    const val ALREADY_IN_PROGRESS: String = "already_in_progress"
}
