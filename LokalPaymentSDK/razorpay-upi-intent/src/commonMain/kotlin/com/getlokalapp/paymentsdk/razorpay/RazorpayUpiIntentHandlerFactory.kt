package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.PaymentGatewayHandler

/**
 * Builds the [PaymentGatewayHandler] for Razorpay's UPI Intent flow if this
 * platform supports it, else returns null — lets a `commonMain` host (e.g. a
 * Compose Multiplatform screen) branch on platform support without knowing
 * [RazorpayUpiIntentSdk] is Android-only. The host is responsible for
 * keeping the returned instance alive (e.g. `remember`) and calling
 * [PaymentGatewayHandler.dispose] when it's done with it — this factory only
 * decides whether to construct one, not how the host manages its lifetime.
 */
expect fun createRazorpayUpiIntentHandler(): PaymentGatewayHandler?
