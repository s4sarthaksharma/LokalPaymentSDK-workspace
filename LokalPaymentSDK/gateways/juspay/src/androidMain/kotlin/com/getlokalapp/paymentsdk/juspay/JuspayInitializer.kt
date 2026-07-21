package com.getlokalapp.paymentsdk.juspay

import android.content.Context
import com.getlokalapp.paymentsdk.GatewayInitializer
import com.getlokalapp.paymentsdk.PaymentGatewayHandler

/**
 * AndroidX App Startup hook that registers [JuspaySdk] with zero host code:
 * returning the object from [create] references it, running its `init` block,
 * which registers it with LokalPaymentSdk. Declared via a manifest
 * `<meta-data>` merged into App Startup's single provider — no per-module
 * authority. Runs synchronously at process start, after `PaymentSdkInitializer`
 * (ordering inherited from [GatewayInitializer]). (iOS gets the same
 * zero-host-code startup via `JuspayEagerInit.kt`.) Registration alone doesn't
 * make Juspay payable yet — the host's [JuspaySdk.initialize] call is still
 * required.
 */
class JuspayInitializer : GatewayInitializer() {

    override fun create(context: Context): PaymentGatewayHandler = JuspaySdk
}
