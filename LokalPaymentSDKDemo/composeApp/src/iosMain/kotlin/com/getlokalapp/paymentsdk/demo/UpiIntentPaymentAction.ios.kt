package com.getlokalapp.paymentsdk.demo

import androidx.compose.runtime.Composable
import com.getlokalapp.paymentsdk.PaymentGatewayHandler

// :razorpay-upi-intent is Android-only (see its build.gradle.kts) — no iOS
// equivalent exists, so there's nothing for this actual to wire up.
@Composable
actual fun rememberUpiIntentPaymentHandler(): PaymentGatewayHandler? = null
