package com.getlokalapp.paymentsdk.upiintent

import android.content.Context
import com.getlokalapp.paymentsdk.GatewayInitializer
import com.getlokalapp.paymentsdk.PaymentGatewayHandler

/**
 * AndroidX App Startup hook that registers [UpiIntentSdk] with zero host code:
 * returning the object from [create] references it, running its `init` block,
 * which registers it with LokalPaymentSdk. Declared via a manifest
 * `<meta-data>` merged into App Startup's single provider. Runs synchronously
 * at process start, after `PaymentSdkInitializer` (ordering inherited from
 * [GatewayInitializer]). Mirrors `RazorpayCustomUiInitializer`.
 */
class UpiIntentInitializer : GatewayInitializer() {

    override fun create(context: Context): PaymentGatewayHandler = UpiIntentSdk
}
