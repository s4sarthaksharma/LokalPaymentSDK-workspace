@file:OptIn(ExperimentalStdlibApi::class)

package com.getlokalapp.paymentsdk.upiintent

import kotlin.native.EagerInitialization

/**
 * ⚠️ LOAD-BEARING "UNUSED" PROPERTY — DO NOT DELETE.
 *
 * iOS counterpart of UpiIntentInitializer: `@EagerInitialization` makes this
 * property initialize before `main()`, which touches [UpiIntentSdk] and runs
 * its registering `init` block — so the gateway is registered with zero host
 * code, same as Android. Unlike `:razorpay-customui`, UPI intent *does* work on
 * iOS (via UIApplication.openURL), so this registers the real handler, not an
 * unavailable stub.
 *
 * Two constraints this imposes (mirrors RazorpayCheckoutEagerInit.kt):
 * - The annotation is experimental and may be dropped by a future Kotlin
 *   release. If it silently becomes a no-op, registration dies with no compile
 *   error and iOS UPI-intent payments start failing with `unsupported_gateway`
 *   — after any Kotlin upgrade, verify on iOS that UPI_INTENT still appears in
 *   `LokalPaymentSdk.gatewayStatus().available`.
 * - This runs pre-main, before UIKit is up. Keep it (and [UpiIntentSdk]'s
 *   `init` block) a bare in-memory registration — no logging, no UIKit. The
 *   only UIKit call (openURL) is inside `pay()`, which runs post-main.
 */
@Suppress("DEPRECATION", "unused")
@EagerInitialization
private val eagerRegistration = UpiIntentSdk
