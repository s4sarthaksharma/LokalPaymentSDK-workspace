package com.getlokalapp.paymentsdk

/**
 * Whatever platform UI context a gateway's own checkout sheet needs to
 * present itself — an Activity on Android, a UIViewController on iOS.
 * Common code treats this as an opaque handle; only platform actuals
 * construct and unwrap it.
 */
expect class PaymentPresenter
