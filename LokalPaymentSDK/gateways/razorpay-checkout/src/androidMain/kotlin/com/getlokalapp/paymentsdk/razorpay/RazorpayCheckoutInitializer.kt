package com.getlokalapp.paymentsdk.razorpay

import android.content.Context
import com.getlokalapp.paymentsdk.GatewayInitializer
import com.getlokalapp.paymentsdk.PaymentGatewayHandler

/**
 * AndroidX App Startup hook that registers [RazorpayCheckoutSdk] with zero
 * host code: returning the object from [create] references it, running its
 * `init` block, which registers it with LokalPaymentSdk. Declared via a
 * manifest `<meta-data>` merged into App Startup's single provider — no
 * per-module authority. Runs synchronously at process start, after
 * `PaymentSdkInitializer` (ordering inherited from [GatewayInitializer]). (iOS
 * gets the same zero-host-code startup via `RazorpayCheckoutEagerInit.kt`.)
 */
class RazorpayCheckoutInitializer : GatewayInitializer() {

    override fun create(context: Context): PaymentGatewayHandler = RazorpayCheckoutSdk
}
