package com.getlokalapp.paymentsdk.model

import kotlinx.serialization.json.Json

// Tolerates extra sibling fields in gateway_config (e.g. order_row_id)
// that CreateOrderResponse/RazorpayCheckoutConfig don't declare — real
// backend responses carry more than the SDK needs.
private val lenientJson = Json { ignoreUnknownKeys = true }

fun parseCreateOrderResponse(json: String): CreateOrderResponse =
    lenientJson.decodeFromString(json)
