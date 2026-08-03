package com.getlokalapp.paymentsdk.webcheckout

import android.content.Context
import com.getlokalapp.paymentsdk.GatewayInitializer
import com.getlokalapp.paymentsdk.PaymentGatewayHandler

/**
 * AndroidX App Startup hook that registers [WebCheckoutGatewayHandler] with zero host code:
 * returning the object from [create] references it, running its `init` block,
 * which registers it with LokalPaymentSdk. Declared via a manifest `<meta-data>`
 * merged into App Startup's single provider. Runs synchronously at process
 * start, after `PaymentSdkInitializer` (ordering inherited from
 * [GatewayInitializer]). Mirrors `UpiIntentInitializer`. (iOS gets the same
 * zero-host-code startup via `WebCheckoutEagerInit.kt`.)
 */
class WebCheckoutInitializer : GatewayInitializer() {

    override fun create(context: Context): PaymentGatewayHandler = WebCheckoutGatewayHandler
}
