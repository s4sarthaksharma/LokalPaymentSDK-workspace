@file:OptIn(ExperimentalStdlibApi::class)

package com.getlokalapp.paymentsdk.razorpay

import kotlin.native.EagerInitialization

/**
 * ⚠️ LOAD-BEARING "UNUSED" PROPERTY — DO NOT DELETE.
 *
 * iOS counterpart of RazorpayCheckoutInitProvider: `@EagerInitialization`
 * makes this property initialize before `main()`, which touches
 * [RazorpayCheckoutSdk] and runs its registering `init` block — so the
 * gateway is registered with zero host code, same as Android.
 *
 * Two constraints this imposes:
 * - The annotation is experimental and may be dropped by a future Kotlin
 *   release. If it silently becomes a no-op, registration dies with no
 *   compile error and iOS Razorpay payments start failing with
 *   `unsupported_gateway` — after any Kotlin upgrade, verify on iOS that
 *   RAZORPAY_CHECKOUT still appears in LokalPaymentSdk.registeredGateways().
 * - This initializer runs pre-main, before UIKit is up. Keep it (and
 *   [RazorpayCheckoutSdk]'s `init` block) a bare in-memory registration —
 *   no logging, no UIKit, no config reads.
 */
@Suppress("DEPRECATION", "unused")
@EagerInitialization
private val eagerRegistration = RazorpayCheckoutSdk
