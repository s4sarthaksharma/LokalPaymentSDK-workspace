package com.getlokalapp.paymentsdk.juspay

import com.getlokalapp.paymentsdk.SdkInitProvider

/**
 * Startup hook (see [SdkInitProvider]) that registers [JuspaySdk] with zero
 * host code: referencing the object runs its `init` block, which registers
 * it with LokalPaymentSdk. Declared in this module's AndroidManifest.xml,
 * merged into the host automatically. (iOS gets the same zero-host-code
 * startup via `JuspayEagerInit.kt`.) Registration alone doesn't make Juspay
 * payable yet — the host's [JuspaySdk.initialize] call is still required.
 */
internal class JuspayInitProvider : SdkInitProvider() {

    override fun onAppStart() {
        JuspaySdk // touching the object registers it
    }
}
