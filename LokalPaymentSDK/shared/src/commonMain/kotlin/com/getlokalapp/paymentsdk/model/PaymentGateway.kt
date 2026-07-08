package com.getlokalapp.paymentsdk.model

import kotlinx.serialization.json.JsonObject

/**
 * Matches the gateway numbering the backend sends in its create-order
 * response; the host maps that number to this enum (via [fromValue]) before
 * handing the SDK a [PaymentOrder]. The number also says which shape to expect
 * in gatewayConfig. Only RAZORPAY_CHECKOUT is wired up in v1 — the rest are
 * reserved so the envelope doesn't change shape when they're added.
 */
enum class PaymentGateway(val value: Int) {
    RAZORPAY_CHECKOUT(1),
    STORE_KIT(2),
    RAZORPAY_INTENT(3),
    JUSPAY(4);

    companion object {
        fun fromValue(value: Int): PaymentGateway? = entries.firstOrNull { it.value == value }
    }
}

/**
 * The typed order the host hands to
 * [com.getlokalapp.paymentsdk.LokalPaymentSdk.pay]. The host calls its own
 * backend to create an order and decodes that gateway/gateway_config
 * response into this shape itself — the SDK neither makes the call nor
 * parses the response.
 *
 * gatewayConfig stays opaque here — it's only parsed once matched against
 * [gateway] (e.g. RazorpayCheckoutConfig for PaymentGateway.RAZORPAY_CHECKOUT).
 */
data class PaymentOrder(
    val gateway: PaymentGateway,
    val gatewayConfig: JsonObject,
)
