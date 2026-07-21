@file:OptIn(ExperimentalStdlibApi::class)

package com.getlokalapp.paymentsdk.webcheckout

import kotlin.native.EagerInitialization

/**
 * ⚠️ LOAD-BEARING "UNUSED" PROPERTY — DO NOT DELETE.
 *
 * iOS counterpart of WebCheckoutInitializer: `@EagerInitialization` makes this
 * property initialize before `main()`, which touches [WebCheckoutSdk] and runs
 * its registering `init` block — so the gateway is registered with zero host
 * code, same as Android. Mirrors `UpiIntentEagerInit.kt`.
 *
 * Two constraints this imposes:
 * - The annotation is experimental and may be dropped by a future Kotlin
 *   release. If it silently becomes a no-op, registration dies with no compile
 *   error and iOS web-checkout payments start failing with `unsupported_gateway`
 *   — after any Kotlin upgrade, verify on iOS that WEB_CHECKOUT still appears in
 *   `LokalPaymentSdk.gatewayStatus().available`.
 * - This runs pre-main, before UIKit is up. Keep it (and [WebCheckoutSdk]'s
 *   `init` block) a bare in-memory registration — no logging, no UIKit. The only
 *   UIKit work (presenting the WebView) is inside `pay()`, which runs post-main.
 */
@Suppress("DEPRECATION", "unused")
@EagerInitialization
private val eagerRegistration = WebCheckoutSdk
