package com.getlokalapp.paymentsdk.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.uikit.LocalUIViewController
import com.getlokalapp.paymentsdk.razorpay.PaymentPresenter

@Composable
actual fun rememberPaymentPresenter(): PaymentPresenter {
    // LocalUIViewController.current is the ComposeUIViewController hosting App(),
    // which is exactly the controller Razorpay should present its sheet on.
    val viewController = LocalUIViewController.current
    return PaymentPresenter(viewController)
}
