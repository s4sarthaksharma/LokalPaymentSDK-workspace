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
// :juspay isn't SPM-migrated yet (see docs/cocoapods-to-spm-migration-plan.md, Phase
// 2.1) — excluded from this SPM build, so its class isn't on the compile classpath.
// import com.getlokalapp.paymentsdk.juspay.JuspaySdk
import com.getlokalapp.paymentsdk.model.LokalPaymentResult
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentOrder
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.upi.UpiApp
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
      "gateway": "razorpay_checkout",
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

// Illustrative only (gateway "razorpay_custom_ui") — a real UPI Intent
// gateway_config also carries which UPI app to hand off to, decided by the
// host's own backend/UI, not shown here.
private val SAMPLE_UPI_INTENT_CREATE_ORDER_RESPONSE = """
{
  "gateway": "razorpay_custom_ui",
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

// Generic UPI-intent gateway ("upi_intent"). gateway_config carries the
// ready-to-launch upi:// deep link the backend built (here a real UPI AutoPay
// mandate URL); the SDK launches it opaquely and emits Pending — the host then
// resolves the real outcome via its own backend. txn_ref is omitted on purpose
// to exercise the SDK's fallback of reading `tr` out of the URL.
//
// allowed_apps is the backend-curated allow-list: the in-SDK chooser shows only
// these apps if installed (Android matches package_name, iOS matches url_scheme)
// and fails with no_upi_app if none are. Drop the field (or send []) to offer
// every installed UPI app instead.
//
// logo_url is the app icon the chooser renders. When present both platforms load
// it; when absent (or the fetch fails) Android falls back to the OS launcher
// icon and iOS to a monogram letter. The URLs below are throwaway placeholders
// that just prove the remote-load path — a real backend sends actual app logos.
private val SAMPLE_GENERIC_UPI_INTENT_CREATE_ORDER_RESPONSE = """
{
  "gateway": "upi_intent",
  "gateway_config": {
    "intent_url": "upi://mandate?mn=Autopay&ver=01&rev=Y&purpose=14&validityend=15072056&QRts=2026-07-15T13:00:22.5741693+05:30&QRexpire=2026-07-15T13:05:21.5741693+05:30&txnType=CREATE&am=299.00&validitystart=15072026&orgId=180001&mode=04&pa=SAHIENGLISHONLINE@ybl&cu=INR&amrule=MAX&fam=2.00&mc=8299&qrMedium=00&recur=ASPRESENTED&mg=ONLINE&share=Y&block=N&tr=OM2607151300225570627163V&pn=Sahi%20English",
    "allowed_apps": [
      { "name": "PhonePe", "package_name": "com.phonepe.app", "url_scheme": "phonepe", "logo_url": "https://placehold.co/96x96/5f259f/ffffff/png?text=PP" },
      { "name": "Google Pay", "package_name": "com.google.android.apps.nbu.paisa.user", "url_scheme": "tez"},
      { "name": "Paytm", "package_name": "net.one97.paytm", "url_scheme": "paytmmp", "logo_url": "https://placehold.co/96x96/00baf2/ffffff/png?text=Paytm" }
    ]
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
      "gateway": "juspay",
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

// Illustrative only (gateway "native_iap"): product_id has to be a real App
// Store Connect in-app-purchase product identifier to actually purchase
// anything — this placeholder will resolve to "Product not found" against a
// real StoreKit call. app_account_token is a real value captured from a
// matrimony sandbox create-order response (R3) — a real host's backend
// generates a fresh one per order, not a constant.
private val SAMPLE_NATIVE_IAP_CREATE_ORDER_RESPONSE = """
{
  "gateway": "native_iap",
  "gateway_config": {
    "product_id": "com.getlokalapp.lokalpaymentsdk.demo.tier1",
    "app_account_token": "2816973c-4c74-4e8d-b7f9-ba2607a4fe7d"
  }
}
""".trimIndent()

// Hosted web-checkout gateway ("web_checkout"). gateway_config carries the
// fully-built hosted-gateway URL the backend assembled (checkoutUrl-encoding +
// provider baked in); the SDK opens it in a WebView and maps the page's reported
// event to PaymentResult. The checkoutUrl below is a PLACEHOLDER — swap in a real
// Dodo session URL from the backend to complete a live payment. As-is, the flow
// runs to Dodo's page and the provider shows a session error (still exercises
// /pay validation + redirect + the WebView presentation).
private val SAMPLE_WEB_CHECKOUT_CREATE_ORDER_RESPONSE = """
{
  "gateway": "web_checkout",
  "gateway_config": {
    "gateway_url": "https://dev-web-pay.dostt.in/?checkoutUrl=https%3A%2F%2Ftest.checkout.dodopayments.com%2Fsession%2Fcks_0Njaeo2VjLvKBQxRRDcE3&provider=dodo"
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
    val gatewayCode = root.getValue("gateway").jsonPrimitive.content
    // Mapping the backend's gateway code to the typed PaymentGateway is now
    // the host's job — the SDK takes an already-resolved enum. An unknown
    // code can't produce a PaymentOrder at all, so we surface it here.
    val gateway = PaymentGateway.fromCode(gatewayCode)
        ?: error("Unknown gateway code from backend: $gatewayCode")
    return PaymentOrder(
        gateway = gateway,
        gatewayConfig = root.getValue("gateway_config").jsonObject,
    )
}

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {

            val gatewayStatus = remember {
                // JuspaySdk.initialize(SAMPLE_JUSPAY_INIT_PAYLOAD, clientId = "lokalmatrimony")
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
                if (PaymentGateway.RAZORPAY_CHECKOUT in registeredGateways) {
                    Button(
                        enabled = !inFlight,
                        onClick = { pay(SAMPLE_CREATE_ORDER_RESPONSE) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Pay with Razorpay")
                    }
                }
                if (PaymentGateway.RAZORPAY_CUSTOM_UI in registeredGateways) {
                    Button(
                        enabled = !inFlight,
                        onClick = { pay(SAMPLE_UPI_INTENT_CREATE_ORDER_RESPONSE) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Pay with Razorpay (UPI Intent)")
                    }
                }
                if (PaymentGateway.UPI_INTENT in registeredGateways) {
                    Button(
                        enabled = !inFlight,
                        onClick = { pay(SAMPLE_GENERIC_UPI_INTENT_CREATE_ORDER_RESPONSE) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Pay with UPI Intent")
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
                if (PaymentGateway.NATIVE_IAP in registeredGateways) {
                    Button(
                        enabled = !inFlight,
                        onClick = { pay(SAMPLE_NATIVE_IAP_CREATE_ORDER_RESPONSE) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Pay with Native IAP (StoreKit)")
                    }
                }
                if (PaymentGateway.WEB_CHECKOUT in registeredGateways) {
                    Button(
                        enabled = !inFlight,
                        onClick = { pay(SAMPLE_WEB_CHECKOUT_CREATE_ORDER_RESPONSE) },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Pay with Web Checkout")
                    }
                }
            }
        }
    }
}

// Formats the UPI apps the SDK detected. Results are platform-shaped: Android
// yields real package names (dynamic PackageManager query), iOS yields URL
// schemes from a curated catalog (canOpenURL) — so we print whichever the
// UpiApp carries.
private fun renderUpiApps(apps: List<UpiApp>): String {
    if (apps.isEmpty()) return "No UPI apps detected"
    return buildString {
        appendLine("Installed UPI apps (${apps.size}):")
        apps.forEach { app ->
            val id = app.packageName ?: app.urlScheme?.let { "$it://" } ?: "—"
            appendLine("• ${app.displayName}  [$id]")
        }
    }.trimEnd()
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

        is PaymentResult.Pending -> """
            Pending (verify with backend)
            txnRef     = ${result.txnRef}
            clientHint = ${result.clientHint}
        """.trimIndent()
    }
    return "$header\n$body"
}
