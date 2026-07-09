package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
