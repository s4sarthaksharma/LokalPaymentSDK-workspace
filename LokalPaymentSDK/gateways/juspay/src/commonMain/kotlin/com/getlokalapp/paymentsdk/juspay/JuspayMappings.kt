package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.json.lenientJson
import com.getlokalapp.paymentsdk.json.toJsonObject
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Everything that crosses the HyperSDK boundary lives here: the inbound
// gateway_config parsing and the outbound process_result classification.

/**
 * Parsed from PaymentOrder.gatewayConfig when gateway is JUSPAY. [sdkPayload]
 * is the ready-made HyperSDK `process` payload — handed straight to
 * process(), never inspected or reshaped (rulebook #5).
 *
 * This wrapper shape (`sdk_payload` nesting, `generated_order_id`) is
 * confirmed against a real `gateway_config` captured from a matrimony
 * sandbox flow (R3).
 */
@Serializable
internal data class JuspayConfig(
    @SerialName("sdk_payload") val sdkPayload: JsonObject,
    @SerialName("generated_order_id") val generatedOrderId: String? = null,
)

/**
 * Decodes the opaque `gateway_config` blob that LokalPaymentSdk already
 * routed to this module. No gateway check is needed here — LokalPaymentSdk
 * only ever hands a JUSPAY config to JuspaySdk.
 */
internal fun JsonObject.toJuspayConfig(): JuspayConfig =
    lenientJson.decodeFromJsonElement(JuspayConfig.serializer(), this)

/** Raw, already-extracted fields from a Juspay process_result event. */
internal data class JuspayResultData(
    val status: String,
    val orderId: String?,
    val txnId: String?,
    val errorCode: String?,
    val errorMessage: String?,
) {
    /** Typed view of [status]; null = status we don't recognize. */
    val parsedStatus: JuspayStatus? get() = JuspayStatus.fromWire(status)
}

/**
 * Juspay's success payload, encoded into [PaymentResult.Success.gatewayData].
 * There is no signature — Juspay's SDK callback returns none — so, unlike the
 * Razorpay gateways, this blob carries only the ids the host's backend needs to
 * verify the charge server-side (`txn_id` = Juspay's epgTxnId).
 */
@Serializable
internal data class JuspaySuccessResult(
    @SerialName("txn_id") val txnId: String,
    @SerialName("order_id") val orderId: String?,
)

/**
 * Classifies Juspay's own statuses into a PaymentResult (D6/D7) — the "one
 * layer up" judgment kept out of the platform clients, mirroring
 * RazorpayResultMapper.
 */
internal fun juspayResultToPaymentResult(data: JuspayResultData): PaymentResult =
    when (data.parsedStatus) {
        JuspayStatus.CHARGED, JuspayStatus.AUTHORIZING, JuspayStatus.PENDING_VBV ->
            PaymentResult.Success(
                JuspaySuccessResult(
                    txnId = data.txnId.orEmpty(),
                    orderId = data.orderId
                ).toJsonObject(),
            )

        JuspayStatus.BACKPRESSED, JuspayStatus.USER_ABORTED ->
            PaymentResult.Cancelled(CancelReason.USER_DISMISSED)

        null ->
            PaymentResult.Failure(
                code = data.errorCode ?: data.status,
                message = data.errorMessage ?: "Juspay payment failed (status=${data.status})",
            )
    }
