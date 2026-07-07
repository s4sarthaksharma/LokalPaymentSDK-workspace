package com.getlokalapp.paymentsdk.demo

import androidx.compose.runtime.Composable
import com.getlokalapp.paymentsdk.PaymentGatewayHandler

/**
 * Builds the [PaymentGatewayHandler] for Razorpay's UPI Intent flow
 * (`:razorpay-upi-intent`) from the ambient Compose context, mirroring
 * [rememberPaymentPresenter]. That module is Android-only, so this returns
 * null on iOS — [App] uses that to skip registering it with [LokalPaymentSdk]
 * and to hide the UPI Intent button. Must be called during composition.
 */
@Composable
expect fun rememberUpiIntentPaymentHandler(): PaymentGatewayHandler?
