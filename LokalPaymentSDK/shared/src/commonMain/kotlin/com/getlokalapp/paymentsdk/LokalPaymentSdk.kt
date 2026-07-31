package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.AvailableGateway
import com.getlokalapp.paymentsdk.model.GatewayCapability
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.GatewayReadiness
import com.getlokalapp.paymentsdk.model.GatewayStatusReport
import com.getlokalapp.paymentsdk.model.LokalPaymentEvent
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import com.getlokalapp.paymentsdk.model.PaymentOrder
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.paymentsdk.model.UnavailableGateway
import com.getlokalapp.paymentsdk.model.describeForLog
import com.getlokalapp.paymentsdk.upi.UpiApp
import com.getlokalapp.paymentsdk.upi.detectInstalledUpiApps
import com.getlokalapp.util.LokalLogger
import com.getlokalapp.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
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

    private const val TAG = "LokalPaymentSdk"

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
     * Runs a payment for the given order and emits [LokalPaymentEvent]s: each wraps a gateway's
     * own [PaymentGatewayEvent] with the resolved [gateway][LokalPaymentEvent.gateway] and the
     * host's [metadata][LokalPaymentEvent.metadata]. The wrapped event is an optional
     * [PaymentGatewayEvent.GatewayUi] pair (see its kdoc for what it means and which gateways emit
     * it), then exactly one terminal [PaymentResult], before completing.
     *
     * @param order the host's create-order response, already decoded into a [PaymentOrder]
     */
    fun pay(order: PaymentOrder): Flow<LokalPaymentEvent> {
        val handler = handlers[order.gateway]
        Log.d { "[$TAG] pay() called for ${order.gateway}, handlerRegistered=${handler != null}" }
        if (handler == null) {
            val error = unavailableError(order.gateway)
            Log.w { "[$TAG] pay() rejected for ${order.gateway}: no handler registered (${error.code})" }
            Log.nonFatal(
                IllegalStateException("pay() called for ${order.gateway} with no handler registered"),
                extras = mapOf("gateway" to order.gateway.name, "reason_code" to (error.code ?: "unknown")),
            ) { "[$TAG] no handler registered for ${order.gateway}" }
            return flowOf(LokalPaymentEvent(order.gateway, error, order.metadata))
        }
        return flow {
            if (!inFlight.tryLock()) {
                Log.w {
                    "[$TAG] pay() rejected for ${order.gateway}: another payment is already in progress"
                }
                emit(LokalPaymentEvent(order.gateway, alreadyInProgressError(), order.metadata))
                return@flow
            }
            try {
                // Every gateway gets the GatewayUi pair. A gateway that renders in-place
                // (SELF_REPORTS_UI, i.e. Juspay) emits its own precise Presented; for the rest the
                // SDK prepends a default one at flow start (handoff). Either way it flows through the
                // same handling below, which tracks it and synthesizes the matching Dismissed just
                // before the terminal — so a host never sees a lone Presented.
                var uiPresented = false
                val gatewayEvents = handler.pay(order.gatewayConfig)
                val selfReportsUi = GatewayCapability.SELF_REPORTS_UI in handler.capabilities
                emitAll(
                    gatewayEvents
                        .onStart { if (!selfReportsUi) emit(PaymentGatewayEvent.GatewayUi.Presented) }
                        .transform { event ->
                            when (event) {
                                is PaymentGatewayEvent.GatewayUi -> {
                                    if (event is PaymentGatewayEvent.GatewayUi.Presented) {
                                        uiPresented = true
                                        Log.d { "[$TAG] ${order.gateway} presented its UI" }
                                    }
                                    emit(LokalPaymentEvent(order.gateway, event, order.metadata))
                                }

                                is PaymentResult -> {
                                    if (uiPresented) {
                                        emit(
                                            LokalPaymentEvent(
                                                order.gateway,
                                                PaymentGatewayEvent.GatewayUi.Dismissed,
                                                order.metadata
                                            )
                                        )
                                    }
                                    logTerminalResult(order.gateway, event)
                                    emit(LokalPaymentEvent(order.gateway, event, order.metadata))
                                }
                            }
                        },
                )
            } finally {
                inFlight.unlock()
            }
        }
    }

    private fun logTerminalResult(gateway: PaymentGateway, result: PaymentResult) {
        val description = "[$TAG] $gateway terminal: ${result.describeForLog()}"
        if (result is PaymentResult.Failure) {
            Log.e { description }
        } else {
            Log.d { description }
        }
    }

    private fun alreadyInProgressError(): PaymentResult.Failure =
        PaymentResult.Failure(
            code = ALREADY_IN_PROGRESS,
            message = "A payment is already in progress; ignoring this concurrent pay() call.",
        )

    private fun unavailableError(gateway: PaymentGateway): PaymentResult.Failure {
        val reason = unavailable[gateway]
        return if (reason != null) {
            PaymentResult.Failure(code = reason.reasonCode, message = reason.reasonMessage)
        } else {
            PaymentResult.Failure(
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
     * Stable [PaymentResult.Failure.code] on the terminal [PaymentResult.Failure] emitted
     * when a [pay] call is rejected because another payment is already in flight
     * (see [inFlight]). Hosts can match on this to silently ignore a double-tap
     * rather than surfacing it as a real failure.
     */
    const val ALREADY_IN_PROGRESS: String = "already_in_progress"
}
