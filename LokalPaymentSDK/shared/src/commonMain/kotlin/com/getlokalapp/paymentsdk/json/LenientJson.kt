package com.getlokalapp.paymentsdk.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Shared across gateway modules' opaque-config decoders (RazorpayCheckoutConfig,
 * RazorpayCustomUiConfig, JuspayConfig, ...) — real gateway_config responses
 * commonly carry extra sibling fields a config data class doesn't declare
 * (e.g. order_row_id), which should be tolerated, not rejected. Also used to
 * encode [com.getlokalapp.paymentsdk.model.GatewayStatusReport.toJson].
 */
val lenientJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Serializes any `@Serializable` receiver to a [JsonObject] via [lenientJson].
 * The gateway modules use it to turn a typed result type into the opaque blob
 * carried on [com.getlokalapp.paymentsdk.model.PaymentResult.Success] /
 * [com.getlokalapp.paymentsdk.model.PaymentResult.Pending], but it's a general
 * encode helper. Reified, so the serializer is resolved at the call site — no
 * `.serializer()` argument and no runtime reflection (safe on Kotlin/Native).
 * The receiver must serialize to a JSON object (a data class), not a primitive
 * or array, or [jsonObject] throws.
 */
inline fun <reified T> T.toJsonObject(): JsonObject =
    lenientJson.encodeToJsonElement(this).jsonObject
