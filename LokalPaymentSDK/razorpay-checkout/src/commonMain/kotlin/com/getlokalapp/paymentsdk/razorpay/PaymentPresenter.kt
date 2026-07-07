package com.getlokalapp.paymentsdk.razorpay

/**
 * Whatever platform UI context Razorpay Checkout needs to present its sheet —
 * an Activity on Android, a UIViewController on iOS. Common code treats this
 * as an opaque handle; only platform actuals construct and unwrap it.
 *
 * Lives in `:razorpay-checkout` because that's the only multiplatform gateway
 * that needs it — `:razorpay-upi-intent` is Android-only and takes a raw
 * Activity instead.
 */
expect class PaymentPresenter
