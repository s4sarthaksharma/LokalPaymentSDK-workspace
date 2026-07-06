package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.model.CreateOrderResponse
import com.getlokalapp.paymentsdk.model.PaymentGateway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CreateOrderResponseMapperTest {

    @Test
    fun parsesRealBackendResponseWithExtraFieldsInGatewayConfig() {
        // Real create-order response body — gateway_config carries an
        // order_row_id sibling field alongside razorpay_key/data that
        // RazorpayCheckoutConfig doesn't declare.
        val json = """
            {
              "gateway": 1,
              "gateway_config": {
                "razorpay_key": "rzp_test_RRHhT2F4OwJ6hF",
                "data": {
                  "name": "Lokal Matrimony",
                  "order_id": "order_TADbMIhkW1BiUx",
                  "currency": "INR",
                  "amount": 19900,
                  "readonly": { "contact": true },
                  "prefill": { "contact": "1111111111" },
                  "method": { "card": false, "upi": true, "netbanking": true, "wallet": true, "emi": false, "paylater": false },
                  "theme": { "color": "#D32F2F" },
                  "KEY_ID": "rzp_test_RRHhT2F4OwJ6hF"
                },
                "order_row_id": 183452
              }
            }
        """.trimIndent()
        val response = Json.decodeFromString<CreateOrderResponse>(json)

        val config = response.toRazorpayCheckoutConfig()

        assertEquals("rzp_test_RRHhT2F4OwJ6hF", config.razorpayKey)
        assertEquals("order_TADbMIhkW1BiUx", config.data["order_id"]?.toString()?.trim('"'))
    }

    @Test
    fun parsesRazorpayCheckoutConfigFromGatewayConfig() {
        val response = CreateOrderResponse(
            gateway = PaymentGateway.RAZORPAY_CHECKOUT.value,
            gatewayConfig = buildJsonObject {
                put("razorpay_key", "rzp_test_key")
                put(
                    "data",
                    buildJsonObject {
                        put("order_id", "order_rzp_123")
                        put("amount", 1000)
                        put("currency", "INR")
                    },
                )
            },
        )

        val config = response.toRazorpayCheckoutConfig()

        assertEquals("rzp_test_key", config.razorpayKey)
        assertEquals("order_rzp_123", config.data["order_id"]?.toString()?.trim('"'))
    }

    @Test
    fun throwsWhenGatewayIsNotRazorpayCheckout() {
        val response = CreateOrderResponse(
            gateway = PaymentGateway.JUSPAY.value,
            gatewayConfig = buildJsonObject { },
        )

        assertFailsWith<IllegalStateException> { response.toRazorpayCheckoutConfig() }
    }
}
