package com.getlokalapp.paymentsdk.android

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentPresenter
import com.getlokalapp.paymentsdk.model.parseCreateOrderResponse
import com.getlokalapp.paymentsdk.razorpay.AndroidRazorpayCheckoutClient
import com.getlokalapp.paymentsdk.razorpay.RazorpayPaymentResultListener
import com.getlokalapp.paymentsdk.razorpay.toRazorpayCheckoutConfig

// A real create-order response captured from the backend, used here to
// exercise the SDK's Razorpay Checkout path without an orchestrator yet.
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

class MainActivity : ComponentActivity() {

    private val razorpayClient = AndroidRazorpayCheckoutClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        razorpayClient.setPaymentResultListener(object : RazorpayPaymentResultListener {
            override fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String) {
                Toast.makeText(this@MainActivity, "Success: $paymentId", Toast.LENGTH_LONG).show()
            }

            override fun onPaymentError(code: Int, description: String?) {
                Toast.makeText(this@MainActivity, "Error [$code]: $description", Toast.LENGTH_LONG).show()
            }
        })

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SdkInfoScreen(
                        info = "LokalPayment SDK ${LokalPaymentSdk.VERSION}",
                        onPayClick = ::openSampleCheckout,
                    )
                }
            }
        }
    }

    private fun openSampleCheckout() {
        val response = parseCreateOrderResponse(SAMPLE_CREATE_ORDER_RESPONSE)
        val config = response.toRazorpayCheckoutConfig()
        razorpayClient.openCheckout(config, PaymentPresenter(this))
    }
}

@Composable
fun SdkInfoScreen(info: String, onPayClick: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = info)
        Button(onClick = onPayClick, modifier = Modifier.padding(top = 16.dp)) {
            Text("Pay with Razorpay")
        }
    }
}

@Preview
@Composable
fun SdkInfoScreenPreview() {
    MaterialTheme {
        SdkInfoScreen("LokalPayment SDK 0.0.1 running on Android")
    }
}
