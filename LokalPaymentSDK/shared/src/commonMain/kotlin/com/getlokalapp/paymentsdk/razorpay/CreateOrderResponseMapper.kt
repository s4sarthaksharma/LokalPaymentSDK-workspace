package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.model.CreateOrderResponse
import com.getlokalapp.paymentsdk.model.PaymentGateway
import kotlinx.serialization.json.Json

// Real backend responses carry extra sibling fields in gateway_config
// (e.g. order_row_id) that RazorpayCheckoutConfig doesn't declare —
// tolerate them the same way gatewayConfig itself is treated as opaque.
private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Throws if the backend responded with anything other than
 * RAZORPAY_CHECKOUT — v1 only wires up this one gateway, so any other
 * value means the host's backend and the SDK have drifted out of sync.
 */
fun CreateOrderResponse.toRazorpayCheckoutConfig(): RazorpayCheckoutConfig {
    val gateway = PaymentGateway.fromValue(this.gateway)
    check(gateway == PaymentGateway.RAZORPAY_CHECKOUT) {
        "Expected gateway RAZORPAY_CHECKOUT, got $gateway (raw=${this.gateway})"
    }
    return lenientJson.decodeFromJsonElement(RazorpayCheckoutConfig.serializer(), gatewayConfig)
}
