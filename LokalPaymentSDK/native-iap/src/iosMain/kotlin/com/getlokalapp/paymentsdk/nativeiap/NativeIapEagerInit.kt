@file:OptIn(ExperimentalStdlibApi::class)

package com.getlokalapp.paymentsdk.nativeiap

import kotlin.native.EagerInitialization

/**
 * ⚠️ LOAD-BEARING "UNUSED" PROPERTY — DO NOT DELETE.
 *
 * `@EagerInitialization` makes this property initialize before `main()`,
 * which touches [NativeIapSdk] and runs its registering `init` block — so the
 * gateway is registered with zero host code (mirrors
 * RazorpayCheckoutEagerInit.kt).
 *
 * Two constraints this imposes:
 * - The annotation is experimental and may be dropped by a future Kotlin
 *   release. If it silently becomes a no-op, registration dies with no
 *   compile error and iOS native IAP payments start failing with
 *   `unsupported_gateway` — after any Kotlin upgrade, verify on iOS that
 *   NATIVE_IAP still appears in `LokalPaymentSdk.gatewayStatus().available`.
 * - This initializer runs pre-main, before UIKit is up. Keep it (and
 *   [NativeIapSdk]'s `init` block) a bare in-memory registration — no
 *   logging, no UIKit, no config reads.
 */
@Suppress("DEPRECATION", "unused")
@EagerInitialization
private val eagerRegistration = NativeIapSdk
