package com.getlokalapp.paymentsdk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What the host app passes into a gateway module's pay() (e.g.
 * RazorpayCheckoutSdk.pay()) after calling its own backend to create an
 * order — the SDK never makes that call itself.
 * gatewayConfig stays opaque here — it's only parsed once matched
 * against `gateway` (e.g. RazorpayCheckoutConfig for
 * PaymentGateway.RAZORPAY_CHECKOUT). Mirrors a gateway/gateway_config
 * response shape 1:1 so a host app whose backend already returns this
 * shape can decode it directly.
 */
@Serializable
data class CreateOrderResponse(
    @SerialName("gateway") val gateway: Int,
    @SerialName("gateway_config") val gatewayConfig: JsonObject,
)

// Tolerates extra sibling fields in gateway_config (e.g. order_row_id)
// that CreateOrderResponse/RazorpayCheckoutConfig don't declare — real
// backend responses carry more than the SDK needs.
private val lenientJson = Json { ignoreUnknownKeys = true }

fun parseCreateOrderResponse(json: String): CreateOrderResponse =
    lenientJson.decodeFromString(json)
