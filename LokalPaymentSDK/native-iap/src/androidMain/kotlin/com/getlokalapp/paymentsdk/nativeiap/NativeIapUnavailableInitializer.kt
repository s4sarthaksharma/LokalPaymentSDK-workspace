package com.getlokalapp.paymentsdk.nativeiap

import android.content.Context
import androidx.startup.Initializer
import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentSdkInitializer
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentGateway

/**
 * Native IAP has no Android implementation yet — Play Billing lands later.
 * Registers [PaymentGateway.NATIVE_IAP] as unavailable at process start
 * (mirrors RazorpayCustomUiEagerInit.kt's iOS-side registration, just on the
 * opposite platform) so a host discovers the reason via
 * LokalPaymentSdk.gatewayStatus() instead of only at pay() time.
 *
 * Not a [com.getlokalapp.paymentsdk.GatewayInitializer] subclass: that base's
 * `create()` must return a real PaymentGatewayHandler, and there's no handler
 * to return here — just an unavailable-registration side effect.
 */
class NativeIapUnavailableInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        LokalPaymentSdk.registerUnavailable(
            gateway = PaymentGateway.NATIVE_IAP,
            reasonCode = "unsupported_platform",
            reasonMessage = "Native IAP (Play Billing) isn't implemented on Android yet.",
            metadata = GatewayMetadata(moduleVersion = MODULE_VERSION, vendorSdkVersion = "unsupported"),
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(PaymentSdkInitializer::class.java)
}
