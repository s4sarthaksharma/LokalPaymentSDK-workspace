package com.getlokalapp.paymentsdk.razorpay

/**
 * Razorpay's UPI Intent flow has no iOS equivalent (see this module's
 * build.gradle.kts) — [RazorpayUpiIntentSdk] stays Android-only, declared
 * only in `androidMain`, and isn't visible from here. This module targets
 * iOS purely so it publishes an iOS variant a consumer can depend on from a
 * single `commonMain` declaration instead of an Android-only source set.
 * There is intentionally no real API surface on this platform.
 */
internal object RazorpayUpiIntentIosStub
