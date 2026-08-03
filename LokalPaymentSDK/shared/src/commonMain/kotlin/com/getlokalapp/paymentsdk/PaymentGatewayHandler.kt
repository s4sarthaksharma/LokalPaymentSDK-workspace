package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.GatewayCapability
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.GatewayReadiness
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.paymentsdk.model.describeForLog
import com.getlokalapp.paymentsdk.json.lenientJson
import com.getlokalapp.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Implemented by each gateway module's own SDK singleton `object` (e.g.
 * RazorpayCheckoutSdk, RazorpayCustomUiSdk, JuspaySdk). Each singleton
 * registers itself with [LokalPaymentSdk] in its `init` block, and the
 * gateway module arranges for that to run at app startup with zero host
 * code — an AndroidX App Startup `Initializer` on Android, an
 * `@EagerInitialization` hook on iOS. The exception is a gateway that needs
 * host-supplied setup data (Juspay's init payload): there the host's one
 * `initialize(...)` call is the trigger. Registration is app-lifetime;
 * handlers are objects, so there is nothing to dispose or unregister.
 *
 * No UI handle to construct with — each concrete handler reads its own
 * current Activity/UIViewController from `:shared`'s hostcontext utilities
 * at call time instead of taking one from the host. [pay] receives only the
 * opaque `gateway_config` blob: [LokalPaymentSdk]
 * has already parsed the create-order envelope and routed by gateway, so the
 * handler never re-parses the response or re-checks the gateway. The blob
 * stays a raw [JsonObject] here because its typed shape (e.g.
 * RazorpayCheckoutConfig) is a gateway-module type [LokalPaymentSdk] can't
 * see — the owning module decodes it.
 */
interface PaymentGatewayHandler {
    val gateway: PaymentGateway
    val metadata: GatewayMetadata

    /**
     * Whether this handler can actually process a payment right now.
     * Checked live on every [LokalPaymentSdk.gatewayStatus] call — unlike
     * registration, this can flip during the app's lifetime (e.g. Juspay
     * becomes [GatewayReadiness.Ready] only after the host calls its
     * `initialize()`). Defaults to always ready: most gateways need no
     * host-supplied setup beyond registration.
     */
    fun readiness(): GatewayReadiness = GatewayReadiness.Ready

    /**
     * Static capabilities the SDK reads to drive gateway-agnostic behavior — currently the
     * [PaymentGatewayEvent.GatewayUi] lifecycle (see [GatewayCapability.SELF_REPORTS_UI]). Empty by
     * default: most gateways rely on the SDK's default handling.
     */
    val capabilities: Set<GatewayCapability> get() = emptySet()

    fun pay(gatewayConfig: JsonObject): Flow<PaymentGatewayEvent>
}

/** Stable, machine-checkable code for a malformed `gateway_config` blob (any gateway). */
const val BAD_GATEWAY_CONFIG: String = "bad_gateway_config"

/** Stable, machine-checkable code reported (never sent to a host) for a duplicate terminal emission. */
private const val DUPLICATE_TERMINAL_RESULT: String = "duplicate_terminal_result"

/**
 * A [PaymentGatewayHandler] whose [pay] is always the same shape: decode `gateway_config` into
 * [T] via [configSerializer], then hand it to [handle]. Gateways implement this instead of
 * [PaymentGatewayHandler] directly and never write `pay(gatewayConfig: JsonObject)` themselves,
 * so routing through [gatewayCallbackFlow] — and with it the decode-or-fail and
 * exactly-one-terminal contracts — can't be skipped. A gateway needing a check *before* the
 * decode puts it at the top of [handle] instead (see `JuspaySdk`'s not-initialized guard).
 *
 * One constraint worth knowing before making a handler public: [T] appears in this interface's
 * member signatures, so a `public` handler needs a `public` [T] — Kotlin forbids a public
 * declaration from exposing an `internal` type. Handlers are `internal` by default, in which case
 * their config type stays `internal` too.
 */
interface TypedPaymentGatewayHandler<T> : PaymentGatewayHandler {
    val configSerializer: KSerializer<T>

    override fun pay(gatewayConfig: JsonObject): Flow<PaymentGatewayEvent> =
        gatewayCallbackFlow(gatewayConfig, configSerializer) { config -> handle(config) }

    suspend fun GatewayResultScope.handle(config: T)
}

/**
 * Runs [block] with a [GatewayResultScope] and [gatewayConfig] already decoded into the gateway's
 * own typed config [T] — the single way a gateway's flow is built, so both the
 * config-decode-or-fail step and the "emit exactly one terminal event" contract are enforced
 * centrally rather than hand-rolled per gateway. [TypedPaymentGatewayHandler.pay] is its only
 * caller in practice; it stays public for a handler that must write its own `pay`.
 *
 * A decode failure becomes a single terminal
 * [com.getlokalapp.paymentsdk.model.PaymentResult.Failure] (code [BAD_GATEWAY_CONFIG]) followed by
 * flow completion — not an uncaught flow exception — before [block] ever runs. The failing gateway
 * is identifiable from the enclosing [com.getlokalapp.paymentsdk.model.LokalPaymentEvent] envelope,
 * so this stays gateway-agnostic.
 *
 * [serializer] is passed as a value rather than derived from [T]: it's an ordinary (non-reified)
 * generic here, since [TypedPaymentGatewayHandler] is an interface and can't reify it.
 */
fun <T> gatewayCallbackFlow(
    gatewayConfig: JsonObject,
    serializer: KSerializer<T>,
    block: suspend GatewayResultScope.(T) -> Unit,
): Flow<PaymentGatewayEvent> = callbackFlow {
    val scope = GatewayResultScope(this)
    val config = runCatching { lenientJson.decodeFromJsonElement(serializer, gatewayConfig) }.getOrElse { e ->
        Log.e(err = e, tag = BAD_GATEWAY_CONFIG) { "Unparseable gateway_config: ${e.message}" }
        Log.nonFatal(
            e,
            extras = mapOf(
                "code" to BAD_GATEWAY_CONFIG,
                "exception_type" to (e::class.simpleName ?: "unknown")
            ),
        ) { "Unparseable gateway_config: ${e.message}" }
        scope.sendTerminal(PaymentResult.Failure(BAD_GATEWAY_CONFIG, "Unparseable gateway_config: ${e.message}"))
        return@callbackFlow
    }
    scope.block(config)
}

/**
 * Scopes a gateway's [PaymentGatewayHandler.pay] body to the two things it's allowed to emit:
 * an optional non-terminal [PaymentGatewayEvent.GatewayUi] signal via [sendUi], and exactly one
 * terminal [PaymentResult] via [sendTerminal]. [awaitClose], [close] and [launch] pass through to
 * the underlying `ProducerScope` unchanged, for cleanup, the bad-config short-circuit, and
 * gateways whose result may arrive on a side channel.
 */
@OptIn(ExperimentalAtomicApi::class)
class GatewayResultScope internal constructor(
    private val producerScope: ProducerScope<PaymentGatewayEvent>,
) {
    private val terminalSent = AtomicReference(false)

    /** Passthrough for the non-terminal [PaymentGatewayEvent.GatewayUi] signal — no guard needed. */
    fun sendUi(event: PaymentGatewayEvent.GatewayUi) {
        producerScope.trySend(event)
    }

    /**
     * Sends [result] as the terminal event and closes the flow. A second call (from any gateway,
     * ever) is a contract violation: it's reported via [Log.nonFatal] and dropped — not forwarded,
     * since a host only expects one. A vendor SDK that re-fires its terminal callback is handled by
     * this drop, so a gateway never needs to guard the call itself.
     */
    fun sendTerminal(result: PaymentResult) {
        if (!terminalSent.compareAndSet(expectedValue = false, newValue = true)) {
            val e = IllegalStateException("Duplicate terminal PaymentResult: ${result.describeForLog()}")
            Log.e(err = e, tag = DUPLICATE_TERMINAL_RESULT) { e.message ?: DUPLICATE_TERMINAL_RESULT }
            Log.nonFatal(e, extras = mapOf("code" to DUPLICATE_TERMINAL_RESULT)) {
                "Gateway attempted a second terminal PaymentResult emission"
            }
            return
        }
        producerScope.trySend(result)
        producerScope.close()
    }

    suspend fun awaitClose(onClose: () -> Unit) = producerScope.awaitClose(onClose)

    fun close(cause: Throwable? = null) = producerScope.close(cause)

    /**
     * Launches [block] as a child of the flow's own scope, for a gateway whose terminal result can
     * arrive on a separate stream instead of the call it started with (`:native-iap` waiting on
     * StoreKit's `transactionUpdates` after a deferred purchase). Cancelled with the flow like any
     * child coroutine. Deliberately a narrow passthrough rather than making this scope a
     * `CoroutineScope`, which would reopen the whole coroutine surface this type exists to narrow.
     */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = producerScope.launch { block() }
}
