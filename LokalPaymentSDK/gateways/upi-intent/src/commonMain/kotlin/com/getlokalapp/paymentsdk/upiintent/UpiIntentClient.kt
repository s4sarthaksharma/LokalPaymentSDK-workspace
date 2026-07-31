package com.getlokalapp.paymentsdk.upiintent

/**
 * Platform launcher for a UPI intent. The common [UpiIntentSdk] owns the flow
 * and the [com.getlokalapp.paymentsdk.model.PaymentResult] mapping; the client
 * only knows how to hand the `upi://…` URL to a UPI app on its platform and
 * report back through [UpiIntentResultListener]. Mirrors
 * `:razorpay-checkout`'s RazorpayCheckoutClient expect/actual split.
 */
internal interface UpiIntentClient {
    fun launch(config: UpiIntentConfig)
    fun setResultListener(listener: UpiIntentResultListener?)
}

/**
 * The two outcomes a UPI intent launch can report on-device. Note there is no
 * "success": once control has been handed to a UPI app, the on-device result
 * is never authoritative (spoofable, often empty, and a debit can succeed even
 * when the app reports failure), so a handoff always maps to
 * [com.getlokalapp.paymentsdk.model.PaymentResult.Pending] and the host must
 * resolve it via its backend.
 */
internal interface UpiIntentResultListener {
    /**
     * Control was handed to a UPI app and returned (Android) or the URL opened
     * (iOS). [clientHint] is the app's unverified status, [ClientStatus.UNKNOWN]
     * when nothing parseable came back (always so on iOS — no callback exists).
     */
    fun onPending(clientHint: ClientStatus)

    /**
     * The launch never reached a UPI app — no app could handle the URL, or the
     * platform had no Activity/foreground context to launch from. No payment
     * was initiated, so this is a real [com.getlokalapp.paymentsdk.model.PaymentResult.Failure].
     */
    fun onFailure(code: String, message: String)

    /**
     * The user dismissed the in-SDK app picker (iOS) without choosing an app,
     * so no UPI app was ever launched. A genuine user cancel — maps to
     * [com.getlokalapp.paymentsdk.model.PaymentResult.Cancelled], distinct from
     * the post-handoff [onPending]. Android never calls this: it hands the
     * `upi://` intent to the OS chooser, so any return is already a handoff.
     */
    fun onCancelled()
}

internal expect fun createUpiIntentClient(): UpiIntentClient
