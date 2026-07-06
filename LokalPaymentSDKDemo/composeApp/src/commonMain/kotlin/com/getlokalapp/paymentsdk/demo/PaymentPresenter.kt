package com.getlokalapp.paymentsdk.demo

import androidx.compose.runtime.Composable
import com.getlokalapp.paymentsdk.PaymentPresenter

/**
 * Builds the platform [PaymentPresenter] from the ambient Compose context —
 * the hosting Activity on Android, the hosting UIViewController on iOS — so the
 * shared [App] composable stays parameterless. Must be called during
 * composition (the iOS actual reads LocalUIViewController).
 */
@Composable
expect fun rememberPaymentPresenter(): PaymentPresenter
