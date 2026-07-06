package com.getlokalapp.paymentsdk.demo

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.getlokalapp.paymentsdk.PaymentPresenter

@Composable
actual fun rememberPaymentPresenter(): PaymentPresenter {
    val activity = checkNotNull(LocalActivity.current) {
        "rememberPaymentPresenter() must be called from an Activity-hosted composition"
    }
    return remember(activity) { PaymentPresenter(activity) }
}
