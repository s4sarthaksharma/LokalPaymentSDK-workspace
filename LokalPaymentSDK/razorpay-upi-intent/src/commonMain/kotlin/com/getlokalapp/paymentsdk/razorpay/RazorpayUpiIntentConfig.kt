package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Parsed from PaymentOrder.gatewayConfig when gateway is
 * RAZORPAY_INTENT. `data` is handed straight to Razorpay's Razorpay.submit()
 * — the SDK never inspects its contents, same treatment as
 * RazorpayCheckoutConfig in `:razorpay-checkout`.
 */
@Serializable
internal data class RazorpayUpiIntentConfig(
    @SerialName("razorpay_key") val razorpayKey: String,
    @SerialName("data") val data: JsonObject,
)

/**
 * Decodes the opaque `gateway_config` blob that LokalPaymentSdk already
 * routed to this module. No gateway check is needed here — LokalPaymentSdk
 * only ever hands a RAZORPAY_INTENT config to RazorpayUpiIntentSdk.
 */
internal fun JsonObject.toRazorpayUpiIntentConfig(): RazorpayUpiIntentConfig =
    lenientJson.decodeFromJsonElement(RazorpayUpiIntentConfig.serializer(), this)
