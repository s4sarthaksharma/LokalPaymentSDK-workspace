package com.getlokalapp.paymentsdk.razorpay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Parsed from PaymentOrder.gatewayConfig when gateway is
 * RAZORPAY_CHECKOUT. `data` is handed straight to Razorpay's own
 * Checkout.open() — the SDK never inspects its contents (amount,
 * currency, order_id, prefill, etc. all live inside it, decided
 * entirely by the host's backend / Razorpay's Orders API).
 */
@Serializable
internal data class RazorpayCheckoutConfig(
    @SerialName("razorpay_key") val razorpayKey: String,
    @SerialName("data") val data: JsonObject,
)
