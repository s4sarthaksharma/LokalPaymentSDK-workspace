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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.juspay.JuspaySdk
import com.getlokalapp.paymentsdk.model.LokalPaymentResult
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentOrder
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.razorpay.RazorpayCheckoutSdk
import com.getlokalapp.paymentsdk.razorpay.createRazorpayUpiIntentHandler
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// A real Juspay init payload captured from a matrimony sandbox flow (R3) —
// a real host gets this from its own backend bootstrap call, not a constant.
private val SAMPLE_JUSPAY_INIT_PAYLOAD = Json.parseToJsonElement(
    """
    {
      "requestId": "3a492806-1039-41a7-bc07-6fa521a27daf",
      "service": "in.juspay.hyperpay",
      "payload": {
        "action": "initiate",
        "clientId": "lokalmatrimony",
        "customerId": "308184",
        "customerPhone": "7837673963",
        "environment": "sandbox",
        "merchantId": "lokalmatrimony"
      },
      "currTime": "2026-07-08T11:43:54Z"
    }
    """.trimIndent(),
).jsonObject

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

// A real gateway_config captured from a matrimony sandbox flow (R3, now
// resolved — matches JuspayConfig's assumed sdk_payload/generated_order_id
// wrapper shape exactly, no decoder changes needed). NOTE: sdk_payload's
// clientAuthToken has a short expiry (clientAuthTokenExpiry) — this sample
// will stop working for a real process() call once that token expires; get a
// fresh one from the backend to actually exercise a live payment.
private val SAMPLE_JUSPAY_CREATE_ORDER_RESPONSE = """
    {
      "gateway": 4,
      "gateway_config": {
        "generated_order_id": "pU7GMJx25h39ogiVtkgq",
        "sdk_payload": {
          "requestId": "099eb8657e8740ddbd441d6e60c1dab6",
          "service": "in.juspay.hyperpay",
          "payload": {
            "clientId": "lokalmatrimony",
            "customerId": "308184",
            "orderId": "pU7GMJx25h39ogiVtkgq",
            "returnUrl": "",
            "currency": "INR",
            "customerEmail": "",
            "customerPhone": "7837673963",
            "service": "in.juspay.hyperpay",
            "description": "matrimony",
            "environment": "sandbox",
            "merchantId": "lokalmatrimony",
            "amount": "199",
            "clientAuthTokenExpiry": "2026-07-08T12:27:15Z",
            "clientAuthToken": "tkn_DbgHo2_h5EBQooMYvbd1SvvB9-rDzUL9_tuE7ejC96vs7fPRTZM7Sc1dcJjG91BhQQ",
            "action": "paymentPage",
            "udf1": "vLDTZDBjeGG8CnfdeTzQ",
            "collectAvsInfo": false
          },
          "currTime": "2026-07-08T12:12:15Z",
          "xRoutingId": "308184"
        }
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
            // No platform handle to grab — RazorpayCheckoutSdk auto-tracks the
            // current Activity on Android and looks up the topmost
            // UIViewController fresh on iOS, same as JuspaySdk below. Just
            // unregisters when this leaves composition.
            val razorpayCheckoutSdk = remember { RazorpayCheckoutSdk() }
            DisposableEffect(razorpayCheckoutSdk) { onDispose { razorpayCheckoutSdk.dispose() } }
            // createRazorpayUpiIntentHandler() returns null on iOS (see its
            // kdoc) — nothing to register there. Registers itself with
            // LokalPaymentSdk when supported; the button below is shown
            // based on that registration, not this return value.
            val upiIntentHandler = remember { createRazorpayUpiIntentHandler() }
            DisposableEffect(upiIntentHandler) { onDispose { upiIntentHandler?.dispose() } }
            // No platform handle to grab — JuspaySdk auto-tracks the current
            // Activity on Android and looks up the topmost UIViewController
            // fresh on iOS. Just unregisters when this leaves composition.
            val juspaySdk = remember { JuspaySdk(SAMPLE_JUSPAY_INIT_PAYLOAD) }
            DisposableEffect(juspaySdk) { onDispose { juspaySdk.dispose() } }
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
                if (PaymentGateway.JUSPAY in registeredGateways) {
                    Button(
                        enabled = !inFlight,
                        onClick = { pay(SAMPLE_JUSPAY_CREATE_ORDER_RESPONSE) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Pay with Juspay")
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
