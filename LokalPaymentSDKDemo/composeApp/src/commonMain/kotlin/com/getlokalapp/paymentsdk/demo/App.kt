package com.getlokalapp.paymentsdk.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.getlokalapp.paymentsdk.juspay.JuspaySdk
import com.getlokalapp.paymentsdk.model.PaymentGateway
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

// Each gated "Pay with …" button is just a (gateway, sample, label) triple.
private data class PaymentDemo(
    val gateway: PaymentGateway,
    val sampleResponse: String,
    val label: String,
)

private val PAYMENT_DEMOS = listOf(
    PaymentDemo(PaymentGateway.RAZORPAY_CHECKOUT, SAMPLE_CREATE_ORDER_RESPONSE, "Pay with Razorpay"),
    PaymentDemo(PaymentGateway.RAZORPAY_CUSTOM_UI, SAMPLE_UPI_INTENT_CREATE_ORDER_RESPONSE, "Pay with Razorpay (UPI Intent)"),
    PaymentDemo(PaymentGateway.UPI_INTENT, SAMPLE_GENERIC_UPI_INTENT_CREATE_ORDER_RESPONSE, "Pay with UPI Intent"),
    PaymentDemo(PaymentGateway.JUSPAY, SAMPLE_JUSPAY_CREATE_ORDER_RESPONSE, "Pay with Juspay"),
    PaymentDemo(PaymentGateway.NATIVE_IAP, SAMPLE_NATIVE_IAP_CREATE_ORDER_RESPONSE, "Pay with Native IAP (StoreKit)"),
    PaymentDemo(PaymentGateway.WEB_CHECKOUT, SAMPLE_WEB_CHECKOUT_CREATE_ORDER_RESPONSE, "Pay with Web Checkout"),
)

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {

            val gatewayStatus = remember {
                JuspaySdk.initialize(SAMPLE_JUSPAY_INIT_PAYLOAD, clientId = "lokalmatrimony")
                LokalPaymentSdk.gatewayStatus()
            }
            val registeredGateways = gatewayStatus.available.map { it.gateway }
            val scope = rememberCoroutineScope()

            var status by remember { mutableStateOf("LokalPayment SDK ${LokalPaymentSdk.VERSION}") }
            var inFlight by remember { mutableStateOf(false) }

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
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .safeDrawingPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SelectionContainer {
                    Text(
                        text = gatewayStatus.toJson(),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                SelectionContainer {
                    Text(text = status)
                }
                Button(
                    onClick = { status = renderUpiApps(LokalPaymentSdk.installedUpiApps()) },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("Detect installed UPI apps")
                }
                PAYMENT_DEMOS
                    .filter { it.gateway in registeredGateways }
                    .forEach { demo ->
                        Button(
                            enabled = !inFlight,
                            onClick = { pay(demo.sampleResponse) },
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text(demo.label)
                        }
                    }
            }
        }
    }
}
