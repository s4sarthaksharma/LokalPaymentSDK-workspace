package com.getlokalapp.paymentsdk.razorpay

/**
 * The underlying Razorpay SDK version this module was built against —
 * differs by platform (Android AAR vs. iOS pod), see the generated
 * `.android.kt`/`.ios.kt` actuals for the current values.
 */
internal expect val VENDOR_SDK_VERSION: String
