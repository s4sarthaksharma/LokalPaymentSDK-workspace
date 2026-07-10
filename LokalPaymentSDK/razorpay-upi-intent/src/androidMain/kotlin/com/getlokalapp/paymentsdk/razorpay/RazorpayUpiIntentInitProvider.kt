package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.SdkInitProvider

/**
 * Startup hook (see [SdkInitProvider]) that registers [RazorpayUpiIntentSdk]
 * with zero host code: referencing the object runs its `init` block, which
 * registers it with LokalPaymentSdk. Declared in this module's
 * AndroidManifest.xml, merged into the host automatically. (No iOS
 * counterpart — this gateway is Android-only, so it just never registers
 * there.)
 */
internal class RazorpayUpiIntentInitProvider : SdkInitProvider() {

    override fun onAppStart() {
        RazorpayUpiIntentSdk // touching the object registers it
    }
}
