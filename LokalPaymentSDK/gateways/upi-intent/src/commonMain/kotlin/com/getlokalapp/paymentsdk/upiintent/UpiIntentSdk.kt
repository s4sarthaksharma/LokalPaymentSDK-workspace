package com.getlokalapp.paymentsdk.upiintent

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.parseGatewayConfigOrFail
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.ClientStatus
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject

/** GatewayMetadata.vendorSdkVersion sentinel — this gateway wraps no vendor SDK. */
private const val NO_VENDOR_SDK = "none"

/**
 * Singleton handler for [PaymentGateway.UPI_INTENT] — registers itself with
 * [LokalPaymentSdk] in its `init` block, which runs at app startup with zero
 * host code: `UpiIntentInitializer` (AndroidX App Startup) touches this object
 * on Android, the `@EagerInitialization` hook in `UpiIntentEagerInit.kt` does
 * on iOS. Unlike the Razorpay/Juspay gateways this one is registered on **both**
 * platforms (iOS UPI intent works via `UIApplication.openURL`), so there is no
 * `registerUnavailable`.
 *
 * No platform handle to grab: the Android client reads the current Activity
 * from `:shared`'s ActivityTracker and iOS uses the shared UIApplication, both
 * at call time. Each [pay] builds its own short-lived platform client.
 */
internal object UpiIntentSdk : PaymentGatewayHandler {

    private const val TAG = "UpiIntent"

    override val gateway: PaymentGateway = PaymentGateway.UPI_INTENT

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = NO_VENDOR_SDK,
    )

    init {
        LokalPaymentSdk.register(this)
    }

    /**
     * Launches the backend-built `upi://…` deep link and emits exactly one
     * terminal [PaymentResult]: [PaymentResult.Pending] once control is handed
     * to a UPI app (the host must then resolve the real outcome via its
     * backend), or [PaymentResult.Failure] if no UPI app could take it. Never
     * a [PaymentResult.Success] — an on-device UPI result can't be trusted.
     */
    override fun pay(gatewayConfig: JsonObject): Flow<PaymentGatewayEvent> = callbackFlow {
        val config = parseGatewayConfigOrFail { gatewayConfig.toUpiIntentConfig() } ?: return@callbackFlow
        val txnRef = config.resolveTxnRef()
        val client = createUpiIntentClient()
        client.setResultListener(object : UpiIntentResultListener {
            override fun onPending(clientHint: ClientStatus) {
                Log.d { "[$TAG] pending, txnRef=$txnRef, clientHint=$clientHint" }
                trySend(PaymentGatewayEvent.Terminal(PaymentResult.Pending(txnRef = txnRef, clientHint = clientHint)))
                close()
            }

            override fun onFailure(code: String, message: String) {
                Log.w { "[$TAG] failure, code=$code, message=$message" }
                trySend(PaymentGatewayEvent.Terminal(PaymentResult.Failure(PaymentError(code = code, message = message))))
                close()
            }

            override fun onCancelled() {
                Log.d { "[$TAG] cancelled by user" }
                trySend(PaymentGatewayEvent.Terminal(PaymentResult.Cancelled(CancelReason.USER_DISMISSED)))
                close()
            }
        })
        Log.d { "[$TAG] launching UPI intent, txnRef=$txnRef" }
        client.launch(config)

        awaitClose { client.setResultListener(null) }
    }
}
