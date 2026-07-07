package com.getlokalapp.paymentsdk.razorpay

/**
 * Lets common orchestration (RazorpayCheckoutSdk.pay) obtain the platform's own
 * Razorpay client without the host having to reach into the razorpay package
 * or know which actual (Android/iOS) backs it.
 */
internal expect fun createRazorpayCheckoutClient(): RazorpayCheckoutClient
