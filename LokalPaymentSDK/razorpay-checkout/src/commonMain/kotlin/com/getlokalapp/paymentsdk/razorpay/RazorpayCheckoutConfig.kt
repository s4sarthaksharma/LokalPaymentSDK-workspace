package com.getlokalapp.paymentsdk.razorpay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

// Real backend responses carry extra sibling fields in gateway_config
// (e.g. order_row_id) that RazorpayCheckoutConfig doesn't declare —
// tolerate them the same way gatewayConfig itself is treated as opaque.
private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Decodes the opaque `gateway_config` blob that LokalPaymentSdk already
 * routed to this module. No gateway check is needed here — LokalPaymentSdk
 * only ever hands a RAZORPAY_CHECKOUT config to RazorpayCheckoutSdk.
 */
internal fun JsonObject.toRazorpayCheckoutConfig(): RazorpayCheckoutConfig =
    lenientJson.decodeFromJsonElement(RazorpayCheckoutConfig.serializer(), this)
