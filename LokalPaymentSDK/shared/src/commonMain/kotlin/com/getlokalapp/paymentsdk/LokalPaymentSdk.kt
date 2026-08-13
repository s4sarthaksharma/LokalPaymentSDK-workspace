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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
@OptIn(ExperimentalAtomicApi::class)
object LokalPaymentSdk {

    private const val TAG = "LokalPaymentSdk"
    private const val FLOW_COMPLETED_WITHOUT_RESULT = "gateway_flow_completed_without_result"
    private const val DEFAULT_PRESENTED_DELAY_MS = 500L

    private val handlers = AtomicReference<Map<PaymentGateway, PaymentGatewayHandler>>(emptyMap())
    private val unavailable = AtomicReference<Map<PaymentGateway, UnavailableGateway>>(emptyMap())

    private val paymentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutablePaymentEvents = MutableSharedFlow<LokalPaymentEvent>()

    /**
     * SDK-wide stream of payment lifecycle and result events. Hosts must start
     * collecting before calling [pay]. Events are not replayed to late collectors.
     */
    val paymentEvents: SharedFlow<LokalPaymentEvent> = mutablePaymentEvents.asSharedFlow()

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
        updateMap(handlers) { it + (handler.gateway to handler) }
    }

    /**
     * Called from a gateway module's own eager startup hook when that
     * module is compiled into the build but structurally can't work on this
     * platform (e.g. Razorpay Custom UI on iOS) — not something a host
     * normally calls directly. [metadata] is still the calling module's own
     * build info, same as a registered handler's [PaymentGatewayHandler.metadata].
     */
    fun registerUnavailable(gateway: PaymentGateway, reasonCode: String, reasonMessage: String, metadata: GatewayMetadata) {
        updateMap(unavailable) {
            it + (gateway to UnavailableGateway(gateway, reasonCode, reasonMessage, metadata))
        }
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
        val handlerSnapshot = handlers.load()
        val unavailableSnapshot = unavailable.load()
        for (handler in handlerSnapshot.values) {
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
            unavailable = unavailableSnapshot.values.toList() + notReady,
        )
    }

    /**
     * Starts a payment for the given order. Events are published to [paymentEvents]: each wraps a gateway's
     * own [PaymentGatewayEvent] with the resolved [gateway][LokalPaymentEvent.gateway] and the
     * host's [metadata][LokalPaymentEvent.metadata]. The wrapped event is an optional
     * [PaymentGatewayEvent.GatewayUi] pair (see its kdoc for what it means and which gateways emit
     * it), then a terminal [PaymentResult]. Every event carries the returned
     * operation ID. This function returns immediately after creating the ID;
     * the host owns repeat-tap prevention and the backend owns payment idempotency.
     *
     * @param order the host's create-order response, already decoded into a [PaymentOrder]
     * @return a unique SDK operation ID for correlating this attempt's events
     */
    fun pay(order: PaymentOrder): String {
        val operationId = Uuid.random().toString()
        val handler = registeredHandlerOrReportUnavailable(operationId, order) ?: return operationId
        paymentScope.launch {
            // Every gateway gets the GatewayUi pair. A gateway that renders in-place
            // (SELF_REPORTS_UI, i.e. Juspay) emits its own precise Presented; for the rest the
            // SDK prepends a default one at flow start (handoff). Either way it flows through the
            // same handling below, which tracks it and synthesizes the matching Dismissed just
            // before the terminal — so a host never sees a lone Presented.
            var uiPresented = false
            var terminalReceived = false
            val selfReportsUi = GatewayCapability.SELF_REPORTS_UI in handler.capabilities
            val eventMutex = Mutex()
            val presentedJob: Job? = if (selfReportsUi) {
                null
            } else {
                launch {
                    delay(DEFAULT_PRESENTED_DELAY_MS)
                    eventMutex.withLock {
                        emit(operationId, order, PaymentGatewayEvent.GatewayUi.Presented)
                        uiPresented = true
                        Log.d { "[$TAG] ${order.gateway} took ownership of payment UI" }
                    }
                }
            }
            flow {
                // Keep creation inside the flow so catch also handles a handler that throws
                // synchronously before returning its gateway event flow.
                emitAll(handler.pay(order.gatewayConfig))
            }
                .catch { throwable -> emit(paymentFlowFailure(order, throwable)) }
                .collect { event ->
                    if (event is PaymentResult) presentedJob?.cancel()
                    eventMutex.withLock {
                        when (event) {
                            is PaymentGatewayEvent.GatewayUi -> {
                                when (event) {
                                    PaymentGatewayEvent.GatewayUi.Presented -> {
                                        uiPresented = true
                                        Log.d { "[$TAG] ${order.gateway} presented its UI" }
                                    }

                                    PaymentGatewayEvent.GatewayUi.Dismissed -> {
                                        uiPresented = false
                                        Log.d { "[$TAG] ${order.gateway} dismissed its UI" }
                                    }
                                }
                            }

                            is PaymentResult -> {
                                terminalReceived = true
                                if (uiPresented) {
                                    emit(operationId, order, PaymentGatewayEvent.GatewayUi.Dismissed)
                                }
                                logTerminalResult(order.gateway, event)
                            }
                        }
                        emit(operationId, order, event)
                    }
                }
            presentedJob?.cancel()
            if (!terminalReceived) {
                val error = IllegalStateException(
                    "Gateway flow completed without a terminal PaymentResult.",
                )
                Log.e(err = error, tag = FLOW_COMPLETED_WITHOUT_RESULT) {
                    "[$TAG] ${order.gateway} flow completed without a terminal result"
                }
                Log.nonFatal(
                    error,
                    extras = mapOf(
                        "gateway" to order.gateway.name,
                        "code" to FLOW_COMPLETED_WITHOUT_RESULT,
                    ),
                ) { "[$TAG] gateway flow contract violated for ${order.gateway}" }
            }
        }
        return operationId
    }

    private fun registeredHandlerOrReportUnavailable(
        operationId: String,
        order: PaymentOrder,
    ): PaymentGatewayHandler? {
        val handler = handlers.load()[order.gateway]
        Log.d { "[$TAG] pay() called for ${order.gateway}, handlerRegistered=${handler != null}" }
        if (handler == null) {
            return rejectPayment(
                operationId = operationId,
                order = order,
                error = unavailableError(order.gateway),
                reason = "no handler registered",
            )
        }

        return when (val readiness = handler.readiness()) {
            GatewayReadiness.Ready -> handler
            is GatewayReadiness.NotReady -> rejectPayment(
                operationId = operationId,
                order = order,
                error = PaymentResult.Failure(
                    code = readiness.reasonCode,
                    message = readiness.reasonMessage,
                ),
                reason = "gateway not ready",
            )
        }
    }

    private fun rejectPayment(
        operationId: String,
        order: PaymentOrder,
        error: PaymentResult.Failure,
        reason: String,
    ): PaymentGatewayHandler? {
        Log.w { "[$TAG] pay() rejected for ${order.gateway}: $reason (${error.code})" }
        Log.nonFatal(
            IllegalStateException("pay() called for ${order.gateway}: $reason"),
            extras = mapOf("gateway" to order.gateway.name, "reason_code" to (error.code ?: "unknown")),
        ) { "[$TAG] $reason for ${order.gateway}" }
        paymentScope.launch { emit(operationId, order, error) }
        return null
    }

    private fun paymentFlowFailure(
        order: PaymentOrder,
        throwable: Throwable,
    ): PaymentResult.Failure {
        Log.nonFatal(throwable, extras = mapOf("gateway" to order.gateway.name)) {
            "[$TAG] ${order.gateway} payment flow failed"
        }
        return PaymentResult.Failure(
            code = "payment_flow_failed",
            message = "Payment flow failed unexpectedly.",
        )
    }

    private suspend fun emit(
        operationId: String,
        order: PaymentOrder,
        event: PaymentGatewayEvent,
    ) {
        mutablePaymentEvents.emit(
            LokalPaymentEvent(
                operationId = operationId,
                gateway = order.gateway,
                event = event,
                metadata = order.metadata,
            ),
        )
    }

    private fun logTerminalResult(gateway: PaymentGateway, result: PaymentResult) {
        val description = "[$TAG] $gateway terminal: ${result.describeForLog()}"
        if (result is PaymentResult.Failure) {
            Log.e { description }
        } else {
            Log.d { description }
        }
    }

    private fun unavailableError(gateway: PaymentGateway): PaymentResult.Failure {
        val reason = unavailable.load()[gateway]
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

    private fun <K, V> updateMap(
        ref: AtomicReference<Map<K, V>>,
        transform: (Map<K, V>) -> Map<K, V>,
    ) {
        while (true) {
            val current = ref.load()
            val next = transform(current)
            if (ref.compareAndSet(current, next)) return
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
    fun setLogger(logger: LokalLogger?) {
        Log = logger ?: com.getlokalapp.util.NoOpLokalLogger
    }

    const val VERSION: String = PAYMENT_SDK_VERSION

}
