package com.getlokalapp.paymentsdk.razorpay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Parsed from PaymentOrder.gatewayConfig when gateway is
 * RAZORPAY_CUSTOM_UI. `data` is handed straight to Razorpay's Razorpay.submit()
 * — the SDK never inspects its contents, same treatment as
 * RazorpayCheckoutConfig in `:razorpay-checkout`.
 */
@Serializable
internal data class RazorpayCustomUiConfig(
    @SerialName("razorpay_key") val razorpayKey: String,
    @SerialName("data") val data: JsonObject,
)
