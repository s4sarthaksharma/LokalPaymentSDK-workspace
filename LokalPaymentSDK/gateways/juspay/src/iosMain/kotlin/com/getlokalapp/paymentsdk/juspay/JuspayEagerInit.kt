@file:OptIn(ExperimentalStdlibApi::class)

package com.getlokalapp.paymentsdk.juspay

import kotlin.native.EagerInitialization

/**
 * ⚠️ LOAD-BEARING "UNUSED" PROPERTY — DO NOT DELETE.
 *
 * iOS counterpart of JuspayInitializer: `@EagerInitialization` makes this
 * property initialize before `main()`, which touches [JuspaySdk] and runs
 * its registering `init` block — so the gateway is registered with zero
 * host code, same as Android (mirrors RazorpayCheckoutEagerInit.kt).
 * Registration alone doesn't make Juspay payable yet — the host's
 * [JuspaySdk.initialize] call is still required.
 *
 * Two constraints this imposes:
 * - The annotation is experimental and may be dropped by a future Kotlin
 *   release. If it silently becomes a no-op, registration dies with no
 *   compile error and iOS Juspay payments start failing with
 *   `unsupported_gateway` — after any Kotlin upgrade, verify on iOS that
 *   JUSPAY still appears in `LokalPaymentSdk.gatewayStatus().available`.
 * - This initializer runs pre-main, before UIKit is up. Keep it (and
 *   [JuspaySdk]'s `init` block) a bare in-memory registration — no logging,
 *   no UIKit, no config reads.
 */
@Suppress("DEPRECATION", "unused")
@EagerInitialization
private val eagerRegistration = JuspaySdk
