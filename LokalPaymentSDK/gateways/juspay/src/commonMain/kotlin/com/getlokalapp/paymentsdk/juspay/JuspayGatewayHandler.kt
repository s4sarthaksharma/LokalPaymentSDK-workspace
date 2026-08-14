package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.GatewayResultScope
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.TypedPaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.GatewayCapability
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.GatewayReadiness
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.util.Log
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Stable, machine-checkable code for a pay() that arrived before [JuspaySdk.configure]. */
internal const val NOT_INITIALIZED_CODE = "juspay_not_initialized"
internal const val NOT_INITIALIZED_MESSAGE = "JuspaySdk.configure() was never called."

/**
 * Singleton handler for [PaymentGateway.JUSPAY] — registers itself with
 * [LokalPaymentSdk] in its `init` block at process start, same as the
 * Razorpay gateways (Android AndroidX App Startup `Initializer` / iOS
 * `@EagerInitialization`, see `JuspayInitializer`/`JuspayEagerInit.kt`).
 * Registration only means "this gateway module is present" — HyperSDK still
 * needs a host-supplied init payload before it can actually pay, so the
 * host's one [JuspaySdk.configure] call remains required (call it once at app
 * startup, same call on both platforms); [pay] fails gracefully with
 * [NOT_INITIALIZED_CODE] if it's invoked first. No platform handle needed
 * from the host: Android auto-tracks the current Activity (see `:shared`'s
 * hostcontext ActivityTracker), iOS looks up the topmost UIViewController
 * fresh whenever HyperSDK needs one.
 *
 * Holds one long-lived [JuspayClient] for the app's lifetime — HyperSDK is
 * initiated once and reused for every [pay] call, not recreated per payment,
 * and never terminated (mirrors matrimony-kmp's confirmed-working design).
 *
 * `internal`, unlike the handler's public [JuspaySdk] façade: this half is gateway plumbing the
 * host never names, which is also what keeps [JuspayConfig] `internal` — a public handler would
 * have to expose its config type in [configSerializer]'s and [handle]'s signatures.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object JuspayGatewayHandler : TypedPaymentGatewayHandler<JuspayConfig> {

    private const val TAG = "Juspay"

    override val gateway: PaymentGateway = PaymentGateway.JUSPAY

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = VENDOR_SDK_VERSION,
    )

    override val configSerializer = JuspayConfig.serializer()

    // HyperSDK renders its UI in-place (Android Fragment) and reports the presented moment via
    // onUiPresented, so the SDK defers to that instead of bracketing the GatewayUi lifecycle.
    override val capabilities: Set<GatewayCapability> = setOf(GatewayCapability.SELF_REPORTS_UI)

    private val client = AtomicReference<JuspayClient?>(null)

    init {
        LokalPaymentSdk.register(this)
    }

    override fun readiness(): GatewayReadiness =
        if (client.load() == null) {
            GatewayReadiness.NotReady(NOT_INITIALIZED_CODE, NOT_INITIALIZED_MESSAGE)
        } else {
            GatewayReadiness.Ready
        }

    /**
     * Backs [JuspaySdk.configure] — see its kdoc for the host-facing contract. Safe to call again
     * to refresh HyperSDK's cached init payload; the underlying client is created once, so
     * [tenantId] is honored from the first call only.
     */
    fun configure(
        initPayload: JsonObject,
        tenantId: String,
    ) {
        Log.d { "[$TAG] configure() called, tenantId=$tenantId, firstTime=${client.load() == null}" }
        getOrCreateInitiatedClient(tenantId, initPayload)
    }

    /**
     * Create-once under races: the CAS loser drops its instance and adopts the winner's. No path
     * to obtain a client without initiating (or re-initiating) it with [initPayload] — [configure]
     * has no separate follow-up step to forget.
     */
    private fun getOrCreateInitiatedClient(
        tenantId: String,
        initPayload: JsonObject
    ): JuspayClient {
        val c = client.load() ?: createJuspayClient(tenantId).let { fresh ->
            if (client.compareAndSet(null, fresh)) fresh else client.load()!!
        }
        c.initiate(initPayload)
        return c
    }

    /**
     * The not-initialized guard lives here rather than ahead of the decode: as a
     * [TypedPaymentGatewayHandler] this gateway no longer writes `pay` itself, so
     * `gateway_config` is decoded first and a malformed blob is reported as
     * `bad_gateway_config` even when [JuspaySdk.configure] was never called. Both outcomes are
     * a single terminal Failure, which is all a host distinguishes on.
     *
     * Reachable both via LokalPaymentSdk.pay() (registration no longer implies
     * [JuspaySdk.configure] has run) and by calling this object's `pay()` directly — either
     * way, fail gracefully rather than crash.
     */
    override suspend fun GatewayResultScope.handle(config: JuspayConfig) {
        val c = client.load() ?: run {
            Log.w { "[$TAG] pay() called before configure()" }
            Log.nonFatal(
                IllegalStateException("JuspayGatewayHandler.pay() called before JuspaySdk.configure()"),
                extras = mapOf("gateway" to "juspay", "operation" to "pay"),
            ) { "[$TAG] pay() called before configure()" }
            sendTerminal(PaymentResult.Failure(NOT_INITIALIZED_CODE, NOT_INITIALIZED_MESSAGE))
            return
        }
        val resultListener = object : JuspayResultListener {
            override fun onUiPresented() {
                Log.d { "[$TAG] UI presented" }
                sendUi(PaymentGatewayEvent.GatewayUi.Presented)
            }

            override fun onResult(data: JsonObject) {
                Log.d {
                    "[$TAG] result received, status=${data.juspayStatus()}, errorCode=${data.juspayErrorCode()}"
                }
                sendTerminal(juspayResultToPaymentResult(data))
            }
        }
        runUntilClosed(
            start = {
                c.setResultListener(resultListener)
                Log.d { "[$TAG] processing payment" }
                c.process(config.sdkPayload)
            },
            // Identity-checked clear: if a newer pay() has already installed
            // its own listener, this stale flow's teardown must not remove it.
            cleanup = { c.clearResultListener(resultListener) },
        )
    }
}
