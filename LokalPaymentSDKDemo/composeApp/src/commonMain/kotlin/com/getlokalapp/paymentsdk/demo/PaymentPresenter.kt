package com.getlokalapp.paymentsdk.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.getlokalapp.paymentsdk.razorpay.PaymentPresenter
import com.getlokalapp.paymentsdk.razorpay.RazorpayCheckoutSdk

/**
 * Builds the platform [PaymentPresenter] from the ambient Compose context —
 * the hosting Activity on Android, the hosting UIViewController on iOS — so the
 * shared [App] composable stays parameterless. Must be called during
 * composition (the iOS actual reads LocalUIViewController).
 */
@Composable
expect fun rememberPaymentPresenter(): PaymentPresenter

/**
 * Builds a [RazorpayCheckoutSdk] for the ambient [PaymentPresenter] and keeps
 * it registered with LokalPaymentSdk for as long as this composable stays in
 * composition — this is host-side Compose glue (RazorpayCheckoutSdk itself
 * stays plain KMP; see docs/rulebook.md in LokalPaymentSDK).
 */
@Composable
fun rememberRazorpayCheckoutSdk(): RazorpayCheckoutSdk {
    val presenter = rememberPaymentPresenter()
    val checkoutSdk = remember(presenter) { RazorpayCheckoutSdk(presenter) }
    DisposableEffect(checkoutSdk) {
        onDispose { checkoutSdk.dispose() }
    }
    return checkoutSdk
}
