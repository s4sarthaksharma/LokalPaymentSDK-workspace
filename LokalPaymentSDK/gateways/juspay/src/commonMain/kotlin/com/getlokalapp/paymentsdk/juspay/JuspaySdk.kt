package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.parseGatewayConfigOrFail
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.GatewayReadiness
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Thrown by [JuspaySdk.prewarm] when [JuspaySdk.configure] hasn't been called yet. */
class JuspayNotConfiguredException(message: String = "JuspaySdk.configure() was never called.") :
    Exception(message)

/**
 * Singleton handler for [PaymentGateway.JUSPAY] — registers itself with
 * [LokalPaymentSdk] in its `init` block at process start, same as the
 * Razorpay gateways (Android AndroidX App Startup `Initializer` / iOS
 * `@EagerInitialization`, see `JuspayInitializer`/`JuspayEagerInit.kt`).
 * Registration only means "this gateway module is present" — HyperSDK still
 * needs a host-supplied init payload before it can actually pay, so the
 * host's one [configure] call remains required (call it once at app
 * startup, same call on both platforms); [pay] fails gracefully with
 * `juspay_not_initialized` if it's invoked first. No platform handle needed
 * from the host: Android auto-tracks the current Activity (see `:shared`'s
 * hostcontext ActivityTracker), iOS looks up the topmost UIViewController
 * fresh whenever HyperSDK needs one.
 *
 * Holds one long-lived [JuspayClient] for the app's lifetime — HyperSDK is
 * initiated once and reused for every [pay] call, not recreated per payment,
 * and never terminated (mirrors matrimony-kmp's confirmed-working design).
 */
@OptIn(ExperimentalAtomicApi::class)
object JuspaySdk : PaymentGatewayHandler {

    override val gateway: PaymentGateway = PaymentGateway.JUSPAY

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = VENDOR_SDK_VERSION,
    )

    private val client = AtomicReference<JuspayClient?>(null)

    private const val NOT_INITIALIZED_CODE = "juspay_not_initialized"
    private const val NOT_INITIALIZED_MESSAGE = "JuspaySdk.configure() was never called."

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
     * Initiates HyperSDK with [initPayload]. Safe to call again — e.g. on
     * resume, or when the payload changes — to refresh HyperSDK's cached
     * init payload; the underlying client is created once, so [tenantId] is
     * honored from the first call only.
     *
     * No clientId parameter — neither platform actual takes a host-supplied
     * one. Android reads it implicitly from the host's Gradle-plugin-injected
     * config; iOS resolves it itself at runtime from the bundled
     * `MerchantConfig.json` (see `IOSJuspayClient`). [tenantId] defaults to
     * Juspay India's shared tenant, correct for every host we have. No
     * onBackPressed forwarding on either platform — confirmed dead code even
     * in matrimony's own shipped implementation.
     */
    fun configure(
        initPayload: JsonObject,
        tenantId: String = "juspayindia",
    ) {
        getOrCreateInitiatedClient(tenantId, initPayload)
    }

    /**
     * Create-once under races: the CAS loser drops its instance and adopts the winner's. No path
     * to obtain a client without initiating (or re-initiating) it with [initPayload] — [configure]
     * has no separate follow-up step to forget.
     */
    private fun getOrCreateInitiatedClient(tenantId: String, initPayload: JsonObject): JuspayClient {
        val c = client.load() ?: createJuspayClient(tenantId).let { fresh ->
            if (client.compareAndSet(null, fresh)) fresh else client.load()!!
        }
        c.initiate(initPayload)
        return c
    }

    /**
     * Re-initiates HyperSDK with whatever [JsonObject] was last passed to [configure] — e.g. to
     * force a fresh handshake on resume without the host having to keep its own copy of the
     * payload around. Named distinctly from [JuspayClient.initiate] (the lower-level per-client
     * op this delegates to): callers here aren't supplying new data, just warming HyperSDK back
     * up. [Throws] is only meaningful on iOS: it makes the compiled Swift function `throws`, so
     * Swift callers must `try` it. Kotlin has no checked exceptions, so on Android/common
     * callers this is purely documentation — an uncaught call still crashes.
     */
    @Throws(JuspayNotConfiguredException::class)
    fun prewarm() {
        val c = client.load() ?: throw JuspayNotConfiguredException()
        val payload = c.cachedInitPayload ?: throw JuspayNotConfiguredException()
        c.initiate(payload)
    }

    override fun pay(gatewayConfig: JsonObject): Flow<PaymentResult> {
        // Reachable both via LokalPaymentSdk.pay() (registration no longer
        // implies configure() has run) and by calling this object's pay()
        // directly — either way, fail gracefully rather than crash.
        val c = client.load() ?: return flowOf(
            PaymentResult.Failure(PaymentError(code = NOT_INITIALIZED_CODE, message = NOT_INITIALIZED_MESSAGE)),
        )
        return callbackFlow {
            // gateway_config comes from the backend — a malformed blob becomes
            // a Failure emission like every other bad state, not a flow crash.
            val config = parseGatewayConfigOrFail { gatewayConfig.toJuspayConfig() } ?: return@callbackFlow
            val resultListener = object : JuspayResultListener {
                override fun onResult(data: JuspayResultData) {
                    trySend(juspayResultToPaymentResult(data))
                    close()
                }
            }
            c.setResultListener(resultListener)
            c.process(config.sdkPayload)
            // Identity-checked clear: if a newer pay() has already installed
            // its own listener, this stale flow's teardown must not remove it.
            awaitClose { c.clearResultListener(resultListener) }
        }
    }
}
