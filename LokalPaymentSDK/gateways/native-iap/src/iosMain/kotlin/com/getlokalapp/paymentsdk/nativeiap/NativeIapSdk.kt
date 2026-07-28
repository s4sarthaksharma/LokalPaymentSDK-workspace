package com.getlokalapp.paymentsdk.nativeiap

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.parseGatewayConfigOrFail
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

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
internal object NativeIapSdk : PaymentGatewayHandler {

    override val gateway: PaymentGateway = PaymentGateway.NATIVE_IAP

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = VENDOR_SDK_VERSION,
    )

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
    override fun pay(gatewayConfig: JsonObject): Flow<PaymentGatewayEvent> = callbackFlow {
        val config = parseGatewayConfigOrFail { gatewayConfig.toNativeIapConfig() } ?: return@callbackFlow
        val client = createNativeIapClient()

        var updatesJob: Job? = null

        fun emitIfTerminal(result: NativeIapPurchaseResult): Boolean {
            val mapped = result.toPaymentResultOrNull() ?: return false
            trySend(PaymentGatewayEvent.Terminal(mapped))
            close()
            return true
        }

        val direct = client.purchase(config.productId, config.appAccountToken)
        if (!emitIfTerminal(direct)) {
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
