@file:OptIn(ExperimentalStdlibApi::class)

package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentGateway
import kotlin.native.EagerInitialization

/**
 * ⚠️ LOAD-BEARING "UNUSED" PROPERTY — DO NOT DELETE.
 *
 * This gateway's UPI Intent flow (resolving installed UPI apps, handing off
 * via an Android Intent) has no iOS equivalent — see this module's
 * build.gradle.kts. `@EagerInitialization` makes this property initialize
 * before `main()` (mirrors RazorpayCheckoutEagerInit.kt), registering
 * [PaymentGateway.RAZORPAY_INTENT] as unavailable here so a host can
 * discover the reason via `LokalPaymentSdk.gatewayStatus()` instead of only
 * finding out at pay() time. vendorSdkVersion is "unsupported" rather than a
 * real version — there is no vendor SDK on this platform to version.
 */
@Suppress("DEPRECATION", "unused")
@EagerInitialization
private val eagerUnavailableRegistration = LokalPaymentSdk.registerUnavailable(
    gateway = PaymentGateway.RAZORPAY_INTENT,
    reasonCode = "unsupported_platform",
    reasonMessage = "Razorpay UPI Intent is Android-only; not available on iOS.",
    metadata = GatewayMetadata(moduleVersion = MODULE_VERSION, vendorSdkVersion = "unsupported"),
)
