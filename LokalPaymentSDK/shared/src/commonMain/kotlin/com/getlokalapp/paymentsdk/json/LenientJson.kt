package com.getlokalapp.paymentsdk.json

import kotlinx.serialization.json.Json

/**
 * Shared across gateway modules' opaque-config decoders (RazorpayCheckoutConfig,
 * RazorpayUpiIntentConfig, JuspayConfig, ...) — real gateway_config responses
 * commonly carry extra sibling fields a config data class doesn't declare
 * (e.g. order_row_id), which should be tolerated, not rejected.
 */
val lenientJson: Json = Json { ignoreUnknownKeys = true }
