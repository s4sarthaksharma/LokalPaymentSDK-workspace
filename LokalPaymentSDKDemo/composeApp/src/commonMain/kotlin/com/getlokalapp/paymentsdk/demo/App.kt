package com.getlokalapp.paymentsdk.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.model.LokalPaymentResult
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentOrder
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// A real create-order response captured from the backend. In a production
// host this JSON comes from the app's own backend create-order call — the
// SDK never makes that call itself; it only consumes the response.
private val SAMPLE_CREATE_ORDER_RESPONSE = """
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

// Illustrative only (gateway 3 = RAZORPAY_INTENT) — a real UPI Intent
// gateway_config also carries which UPI app to hand off to, decided by the
// host's own backend/UI, not shown here.
private val SAMPLE_UPI_INTENT_CREATE_ORDER_RESPONSE = """
{
  "gateway": 3,
  "gateway_config": {
    "razorpay_key": "rzp_live_RRHjf8hhNwEqrS",
    "data": {
      "order_id": "order_TAWpZGsiCtjdyF",
      "currency": "INR",
      "amount": 19900,
      "contact": "1233214422",
      "upi_app_package_name": "com.phonepe.app",
      "method": "upi",
      "email": "someone@example.com",
      "_[flow]": "intent"
    },
    "order_row_id": 3299386
  }
}
""".trimIndent()

// The SDK expects a typed PaymentOrder and no longer parses JSON itself, so
// the host does it. A real host would decode into its own backend DTOs; here
// we pull the two fields the SDK needs straight off the JSON tree.
// ignoreUnknownKeys-style leniency is implicit with this element API — the
// extra sibling fields (e.g. order_row_id) are simply left unread.
private val orderJson = Json { ignoreUnknownKeys = true }

private fun parseOrder(orderResponseJson: String): PaymentOrder {
    val root = orderJson.parseToJsonElement(orderResponseJson).jsonObject
    val gatewayValue = root.getValue("gateway").jsonPrimitive.int
    // Mapping the backend's gateway number to the typed PaymentGateway is now
    // the host's job — the SDK takes an already-resolved enum. An unknown
    // value can't produce a PaymentOrder at all, so we surface it here.
    val gateway = PaymentGateway.fromValue(gatewayValue)
        ?: error("Unknown gateway value from backend: $gatewayValue")
    return PaymentOrder(
        gateway = gateway,
        gatewayConfig = root.getValue("gateway_config").jsonObject,
    )
}

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // Registers itself with LokalPaymentSdk and unregisters when this
            // leaves composition — see rememberRazorpayCheckoutSdk's kdoc.
            rememberRazorpayCheckoutSdk()
            // Android-only (see rememberUpiIntentPaymentHandler's kdoc) —
            // a no-op on iOS. Registers itself with LokalPaymentSdk when
            // supported; the button below is shown based on that
            // registration, not this return value.
            rememberUpiIntentPaymentHandler()
            val scope = rememberCoroutineScope()

            var status by remember { mutableStateOf("LokalPayment SDK ${LokalPaymentSdk.VERSION}") }
            var inFlight by remember { mutableStateOf(false) }
            // Registration above already finished synchronously by this point,
            // and nothing unregisters while App() stays composed — so this is
            // safe to compute once and cache, rather than on every recomposition.
            val registeredGateways = remember { LokalPaymentSdk.registeredGateways() }

            // Both buttons call this with a different sample response — which
            // gateway handles the payment is decided entirely by the "gateway"
            // field inside orderResponseJson, exactly as it would be decided by
            // the host's own backend in production. The host parses the
            // response into a PaymentOrder here; the SDK takes it typed.
            fun pay(orderResponseJson: String) {
                scope.launch {
                    inFlight = true
                    val order = runCatching { parseOrder(orderResponseJson) }
                        .getOrElse {
                            status = "Error: ${it.message}"
                            inFlight = false
                            return@launch
                        }
                    LokalPaymentSdk.pay(order)
                        .catch { status = "Error: ${it.message}" }
                        .collect { status = render(it) }
                    inFlight = false
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = status)
                if (PaymentGateway.RAZORPAY_CHECKOUT in registeredGateways) {
                    Button(
                        enabled = !inFlight,
                        onClick = { pay(SAMPLE_CREATE_ORDER_RESPONSE) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Pay with Razorpay")
                    }
                }
                if (PaymentGateway.RAZORPAY_INTENT in registeredGateways) {
                    Button(
                        enabled = !inFlight,
                        onClick = { pay(SAMPLE_UPI_INTENT_CREATE_ORDER_RESPONSE) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Pay with Razorpay (UPI Intent)")
                    }
                }
            }
        }
    }
}

// Dumps the entire object the SDK hands back — the routing gateway from the
// LokalPaymentResult envelope plus every field of the inner PaymentResult.
private fun render(payment: LokalPaymentResult): String {
    val header = "gateway = ${payment.gateway}"
    val body = when (val result = payment.result) {
        is PaymentResult.Success -> """
            Success
            paymentId = ${result.paymentId}
            orderId   = ${result.orderId ?: "—"}
            signature = ${result.signature}
        """.trimIndent()

        is PaymentResult.Cancelled -> """
            Cancelled
            reason = ${result.reason}
        """.trimIndent()

        is PaymentResult.Failure -> """
            Failure
            code    = ${result.error.code ?: "—"}
            message = ${result.error.message}
        """.trimIndent()
    }
    return "$header\n$body"
}
