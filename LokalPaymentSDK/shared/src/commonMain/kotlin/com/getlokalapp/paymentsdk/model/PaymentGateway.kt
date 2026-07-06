package com.getlokalapp.paymentsdk.model

/**
 * Matches the gateway numbering the backend uses in CreateOrderResponse.gateway
 * to say which shape to expect in gatewayConfig. Only RAZORPAY_CHECKOUT is
 * wired up in v1 — the rest are reserved so the envelope doesn't change shape
 * when they're added.
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
