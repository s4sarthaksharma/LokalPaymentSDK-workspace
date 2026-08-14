package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * Classifies Juspay's complete `process_result` object while preserving it
 * unchanged as the gateway blob for successful and pending outcomes. Only the
 * fields needed to choose the public result subtype or describe a failure are
 * inspected; the SDK does not reconstruct a provider-specific result schema.
 */
internal fun juspayResultToPaymentResult(data: JsonObject): PaymentResult {
    val status = data.juspayStatus()
    return when (JuspayStatus.fromWire(status)) {
        JuspayStatus.CHARGED ->
            PaymentResult.Success(data)

        JuspayStatus.AUTHORIZING, JuspayStatus.PENDING_VBV ->
            PaymentResult.Pending(data)

        JuspayStatus.BACKPRESSED, JuspayStatus.USER_ABORTED ->
            PaymentResult.Cancelled(CancelReason.USER_DISMISSED)

        null ->
            PaymentResult.Failure(
                code = data.juspayErrorCode() ?: status,
                message = data.juspayErrorMessage() ?: "Juspay payment failed (status=$status)",
                gatewayData = data,
            )
    }
}

internal fun JsonObject.juspayStatus(): String = payloadString("status").orEmpty()

internal fun JsonObject.juspayErrorCode(): String? = string("errorCode")

internal fun JsonObject.juspayErrorMessage(): String? = string("errorMessage")

private fun JsonObject.payloadString(key: String): String? =
    (this["payload"] as? JsonObject)?.string(key) ?: string(key)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
