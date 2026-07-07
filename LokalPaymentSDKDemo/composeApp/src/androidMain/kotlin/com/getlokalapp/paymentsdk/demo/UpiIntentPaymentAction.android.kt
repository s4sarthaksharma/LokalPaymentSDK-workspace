package com.getlokalapp.paymentsdk.demo

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.razorpay.RazorpayUpiIntentSdk

@Composable
actual fun rememberUpiIntentPaymentHandler(): PaymentGatewayHandler? {
    val activity = checkNotNull(LocalActivity.current) {
        "rememberUpiIntentPaymentHandler() must be called from an Activity-hosted composition"
    }
    // Constructing RazorpayUpiIntentSdk registers it with LokalPaymentSdk.
    // It launches its own internal proxy Activity to drive the payment (owns
    // its own WebView, handles its own onActivityResult) — nothing else to
    // wire up here.
    val sdk = remember(activity) { RazorpayUpiIntentSdk(activity) }

    DisposableEffect(sdk) {
        onDispose { sdk.dispose() }
    }

    return sdk
}
