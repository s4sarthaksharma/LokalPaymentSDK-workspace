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
import com.getlokalapp.paymentsdk.demo.SAMPLE_CREATE_ORDER_RESPONSE
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

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

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val sdk = remember { LokalPaymentSdk() }
            // Read the presenter during composition — on iOS the underlying
            // LocalUIViewController must be resolved here, not inside a later
            // coroutine.
            val presenter = rememberPaymentPresenter()
            val scope = rememberCoroutineScope()

            var status by remember { mutableStateOf("LokalPayment SDK ${LokalPaymentSdk.VERSION}") }
            var inFlight by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = status)
                Button(
                    enabled = !inFlight,
                    onClick = {
                        scope.launch {
                            inFlight = true
                            sdk.pay(SAMPLE_CREATE_ORDER_RESPONSE, presenter)
                                .catch { status = "Error: ${it.message}" }
                                .collect { status = render(it) }
                            inFlight = false
                        }
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("Pay with Razorpay")
                }
            }
        }
    }
}

private fun render(result: PaymentResult): String = when (result) {
    is PaymentResult.Success -> "Success: ${result.paymentId}"
    is PaymentResult.Cancelled -> "Cancelled: ${result.reason}"
    is PaymentResult.Failure -> "Error [${result.error.code}]: ${result.error.message}"
}
