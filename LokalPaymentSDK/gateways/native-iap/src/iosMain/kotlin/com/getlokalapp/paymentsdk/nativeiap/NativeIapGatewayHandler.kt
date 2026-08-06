package com.getlokalapp.paymentsdk.nativeiap

import com.getlokalapp.paymentsdk.GatewayResultScope
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.TypedPaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.GatewayCapability
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import com.getlokalapp.paymentsdk.model.describeForLog
import com.getlokalapp.util.Log
import kotlinx.coroutines.Job

/**
 * Singleton handler for [PaymentGateway.NATIVE_IAP] — registers itself with
 * [LokalPaymentSdk] in its `init` block, which the `@EagerInitialization` hook
 * in `NativeIapEagerInit.kt` runs at app startup with zero host code. iOS-only
 * for now (see this module's build.gradle.kts): Android registers itself as
 * unavailable instead (`NativeIapUnavailableInitializer`) until Play Billing
 * lands, at which point this object's `pay` orchestration — decode config,
 * drive [NativeIapClient], collapse the result — becomes shared and only the
 * concrete client differs per platform.
 */
internal object NativeIapGatewayHandler : TypedPaymentGatewayHandler<NativeIapConfig> {

    private const val TAG = "NativeIap"

    override val gateway: PaymentGateway = PaymentGateway.NATIVE_IAP

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = VENDOR_SDK_VERSION,
    )

    // StoreKit's sheet doesn't come up when pay() is called — resolving the product with the App
    // Store happens first, and that round trip runs into seconds when cold or in Sandbox. The
    // SDK's default Presented (emitted at flow start) would tell a host the sheet is up while the
    // screen is still blank, so this gateway reports its own from the handoff point instead.
    override val capabilities: Set<GatewayCapability> = setOf(GatewayCapability.SELF_REPORTS_UI)

    override val configSerializer = NativeIapConfig.serializer()

    init {
        LokalPaymentSdk.register(this)
    }

    /**
     * Emits exactly one terminal [PaymentResult] before completing, same
     * contract as every other gateway. The wrinkle here: a purchase can
     * resolve [NativeIapPurchaseResult.Pending] (e.g. Ask to Buy, SCA)
     * instead of terminating immediately — in that case this stays open and
     * waits on [NativeIapClient.transactionUpdates] for the matching
     * terminal transaction rather than treating Pending itself as terminal.
     */
    override suspend fun GatewayResultScope.handle(config: NativeIapConfig) {
        val client = createNativeIapClient()

        var updatesJob: Job? = null

        fun emitIfTerminal(result: NativeIapPurchaseResult): Boolean {
            val mapped = result.toPaymentResultOrNull() ?: return false
            Log.d { "[$TAG] settling with ${mapped.describeForLog()}" }
            sendTerminal(mapped)
            return true
        }

        Log.d { "[$TAG] purchasing productId=${config.productId}" }
        val direct = client.purchase(config.productId, config.appAccountToken) {
            Log.d { "[$TAG] handing off to StoreKit for productId=${config.productId}" }
            sendUi(PaymentGatewayEvent.GatewayUi.Presented)
        }
        if (!emitIfTerminal(direct)) {
            Log.d { "[$TAG] purchase pending, waiting on transactionUpdates for productId=${config.productId}" }
            updatesJob = launch {
                client.transactionUpdates.collect { update ->
                    // A Success from transactionUpdates might belong to a
                    // different, unrelated product than the one this pay()
                    // call is waiting on (StoreKit reports all deferred
                    // transactions on one shared stream) — only treat a
                    // matching product as resolving this call.
                    if (update is NativeIapPurchaseResult.Success && update.productId != config.productId) return@collect
                    emitIfTerminal(update)
                }
            }
        }

        awaitClose { updatesJob?.cancel() }
    }
}
