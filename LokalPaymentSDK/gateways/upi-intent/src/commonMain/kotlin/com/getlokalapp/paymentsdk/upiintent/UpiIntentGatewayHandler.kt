package com.getlokalapp.paymentsdk.upiintent

import com.getlokalapp.paymentsdk.GatewayResultScope
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.TypedPaymentGatewayHandler
import com.getlokalapp.paymentsdk.json.toJsonObject
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
internal object UpiIntentGatewayHandler : TypedPaymentGatewayHandler<UpiIntentConfig> {

    private const val TAG = "UpiIntent"

    override val gateway: PaymentGateway = PaymentGateway.UPI_INTENT

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = NO_VENDOR_SDK,
    )

    override val configSerializer = UpiIntentConfig.serializer()

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
    override suspend fun GatewayResultScope.handle(config: UpiIntentConfig) {
        val txnRef = config.resolveTxnRef()
        val client = createUpiIntentClient()
        val listener = object : UpiIntentResultListener {
            override fun onPending(clientHint: ClientStatus) {
                Log.d { "[$TAG] pending, txnRef=$txnRef, clientHint=$clientHint" }
                sendTerminal(
                    PaymentResult.Pending(
                        UpiIntentPendingResult(
                            txnRef = txnRef,
                            clientHint = clientHint.name
                        ).toJsonObject()
                    )
                )
            }

            override fun onFailure(code: String, message: String) {
                Log.w { "[$TAG] failure, code=$code, message=$message" }
                sendTerminal(PaymentResult.Failure(code, message))
            }

            override fun onCancelled() {
                Log.d { "[$TAG] cancelled by user" }
                sendTerminal(PaymentResult.Cancelled(CancelReason.USER_DISMISSED))
            }

            override fun onUiDestroyed(afterHandoff: Boolean) {
                if (afterHandoff) {
                    // Indistinguishable from a normal return, and for the same reason: control was
                    // with a UPI app, so only the backend knows whether money moved.
                    Log.w { "[$TAG] UI destroyed after handoff, txnRef=$txnRef, resolving as pending" }
                    sendTerminal(
                        PaymentResult.Pending(
                            UpiIntentPendingResult(
                                txnRef = txnRef,
                                clientHint = ClientStatus.UNKNOWN.name,
                            ).toJsonObject()
                        )
                    )
                } else {
                    Log.w { "[$TAG] UI destroyed before any UPI app was launched, txnRef=$txnRef" }
                    sendTerminal(PaymentResult.Cancelled(CancelReason.UI_DESTROYED))
                }
            }
        }
        runUntilClosed(
            start = {
                client.setResultListener(listener)
                Log.d { "[$TAG] launching UPI intent, txnRef=$txnRef" }
                client.launch(config)
            },
            cleanup = { client.setResultListener(null) },
        )
    }
}

/**
 * The Pending payload encoded into [PaymentResult.Pending.gatewayData]: the
 * merchant transaction reference the host's backend polls on, plus the advisory
 * [ClientStatus] the UPI app reported (never authoritative — UX flavor only).
 */
@Serializable
private data class UpiIntentPendingResult(
    @SerialName("txn_ref") val txnRef: String,
    @SerialName("client_hint") val clientHint: String,
)
