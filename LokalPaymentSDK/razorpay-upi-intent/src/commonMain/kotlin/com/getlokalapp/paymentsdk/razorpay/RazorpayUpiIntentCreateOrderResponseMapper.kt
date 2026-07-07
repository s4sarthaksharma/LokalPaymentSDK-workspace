package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.model.CreateOrderResponse
import com.getlokalapp.paymentsdk.model.PaymentGateway
import kotlinx.serialization.json.Json

// Deliberately not named CreateOrderResponseMapper.kt like
// `:razorpay-checkout`'s file — same package, so an identical file name
// would compile to a duplicate JVM class name (CreateOrderResponseMapperKt)
// and collide for any consumer depending on both modules (as this repo's own
// demo app now does).

// Real backend responses carry extra sibling fields in gateway_config
// (e.g. order_row_id) that RazorpayUpiIntentConfig doesn't declare —
// tolerate them the same way gatewayConfig itself is treated as opaque.
private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Throws if the backend responded with anything other than
 * RAZORPAY_INTENT — any other value means the host's backend and the SDK
 * have drifted out of sync.
 */
fun CreateOrderResponse.toRazorpayUpiIntentConfig(): RazorpayUpiIntentConfig {
    val gateway = PaymentGateway.fromValue(this.gateway)
    check(gateway == PaymentGateway.RAZORPAY_INTENT) {
        "Expected gateway RAZORPAY_INTENT, got $gateway (raw=${this.gateway})"
    }
    return lenientJson.decodeFromJsonElement(RazorpayUpiIntentConfig.serializer(), gatewayConfig)
}
