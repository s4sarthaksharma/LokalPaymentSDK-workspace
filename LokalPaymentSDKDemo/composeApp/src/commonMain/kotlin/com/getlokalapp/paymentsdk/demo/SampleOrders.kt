package com.getlokalapp.paymentsdk.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// A real Juspay init payload captured from a matrimony sandbox flow (R3) —
// a real host gets this from its own backend bootstrap call, not a constant.
internal val SAMPLE_JUSPAY_INIT_PAYLOAD = Json.parseToJsonElement(
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
internal val SAMPLE_CREATE_ORDER_RESPONSE = """
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
      },
      "metadata": {
        "source": "demo_checkout_screen",
        "order_ref": "LKL-RZP-183452"
      }
    }
""".trimIndent()

// Illustrative only (gateway "razorpay_custom_ui") — a real UPI Intent
// gateway_config also carries which UPI app to hand off to, decided by the
// host's own backend/UI, not shown here.
internal val SAMPLE_UPI_INTENT_CREATE_ORDER_RESPONSE = """
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
  },
  "metadata": {
    "source": "demo_checkout_screen",
    "order_ref": "LKL-RZPUPI-3299386"
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
internal val SAMPLE_GENERIC_UPI_INTENT_CREATE_ORDER_RESPONSE = """
{
  "gateway": "upi_intent",
  "gateway_config": {
    "intent_url": "upi://mandate?mn=Autopay&ver=01&rev=Y&purpose=14&validityend=15072056&QRts=2026-07-15T13:00:22.5741693+05:30&QRexpire=2026-07-15T13:05:21.5741693+05:30&txnType=CREATE&am=299.00&validitystart=15072026&orgId=180001&mode=04&pa=SAHIENGLISHONLINE@ybl&cu=INR&amrule=MAX&fam=2.00&mc=8299&qrMedium=00&recur=ASPRESENTED&mg=ONLINE&share=Y&block=N&tr=OM2607151300225570627163V&pn=Sahi%20English",
    "allowed_apps": [
      { "name": "PhonePe", "package_name": "com.phonepe.app", "url_scheme": "phonepe", "logo_url": "https://placehold.co/96x96/5f259f/ffffff/png?text=PP" },
      { "name": "Google Pay", "package_name": "com.google.android.apps.nbu.paisa.user", "url_scheme": "tez"},
      { "name": "Paytm", "package_name": "net.one97.paytm", "url_scheme": "paytmmp", "logo_url": "https://placehold.co/96x96/00baf2/ffffff/png?text=Paytm" }
    ]
  },
  "metadata": {
    "source": "demo_checkout_screen",
    "order_ref": "LKL-UPI-OM2607151300225570627163V"
  }
}
""".trimIndent()

// A real gateway_config captured from a matrimony sandbox flow (R3, now
// resolved — matches JuspayConfig's assumed sdk_payload/generated_order_id
// wrapper shape exactly, no decoder changes needed). NOTE: sdk_payload's
// clientAuthToken has a short expiry (clientAuthTokenExpiry) — this sample
// will stop working for a real process() call once that token expires; get a
// fresh one from the backend to actually exercise a live payment.
internal val SAMPLE_JUSPAY_CREATE_ORDER_RESPONSE = """
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
      },
      "metadata": {
        "source": "demo_checkout_screen",
        "order_ref": "LKL-JUSPAY-pU7GMJx25h39ogiVtkgq"
      }
    }
""".trimIndent()

// Illustrative only (gateway "native_iap"): product_id has to be a real App
// Store Connect in-app-purchase product identifier to actually purchase
// anything — this placeholder will resolve to "Product not found" against a
// real StoreKit call. app_account_token is a real value captured from a
// matrimony sandbox create-order response (R3) — a real host's backend
// generates a fresh one per order, not a constant.
internal val SAMPLE_NATIVE_IAP_CREATE_ORDER_RESPONSE = """
{
  "gateway": "native_iap",
  "gateway_config": {
    "product_id": "com.getlokalapp.lokalpaymentsdk.demo.tier1",
    "app_account_token": "2816973c-4c74-4e8d-b7f9-ba2607a4fe7d"
  },
  "metadata": {
    "source": "demo_checkout_screen",
    "order_ref": "LKL-IAP-2816973c"
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
internal val SAMPLE_WEB_CHECKOUT_CREATE_ORDER_RESPONSE = """
{
  "gateway": "web_checkout",
  "gateway_config": {
    "gateway_url": "https://dev-web-pay.dostt.in/?checkoutUrl=https%3A%2F%2Ftest.checkout.dodopayments.com%2Fsession%2Fcks_0Njaeo2VjLvKBQxRRDcE3&provider=dodo"
  },
  "metadata": {
    "source": "demo_checkout_screen",
    "order_ref": "LKL-WEB-cks_0Njaeo2VjLvKBQxRRDcE3"
  }
}
""".trimIndent()
