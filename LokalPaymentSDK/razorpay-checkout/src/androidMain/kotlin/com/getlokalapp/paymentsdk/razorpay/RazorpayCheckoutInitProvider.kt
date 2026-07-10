package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.SdkInitProvider

/**
 * Startup hook (see [SdkInitProvider]) that registers [RazorpayCheckoutSdk]
 * with zero host code: referencing the object runs its `init` block, which
 * registers it with LokalPaymentSdk. Declared in this module's
 * AndroidManifest.xml, merged into the host automatically. (iOS gets the
 * same zero-host-code startup via `RazorpayCheckoutEagerInit.kt`.)
 */
internal class RazorpayCheckoutInitProvider : SdkInitProvider() {

    override fun onAppStart() {
        RazorpayCheckoutSdk // touching the object registers it
    }
}
