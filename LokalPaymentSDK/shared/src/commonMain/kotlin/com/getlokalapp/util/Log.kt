package com.getlokalapp.util

import kotlin.concurrent.Volatile

/**
 * Implemented by the host to receive structured logs from every SDK module. Not part of the
 * public integration surface directly — hosts pass an instance to
 * [com.getlokalapp.paymentsdk.LokalPaymentSdk.setLogger], which installs it as [Log].
 */
interface LokalLogger {
    fun d(forceLog: Boolean = false, message: () -> String)
    fun i(forceLog: Boolean = false, message: () -> String)
    fun w(forceLog: Boolean = false, message: () -> String)
    fun e(err: Throwable? = null, tag: String? = null, forceLog: Boolean = false, message: () -> String)
}

/**
 * The process-wide logger every SDK module logs through — call it directly, e.g.
 * `Log.d { "checkout started" }`. Defaults to [NoOpLokalLogger], which drops everything
 * (and never invokes the message lambda), until a host installs a real [LokalLogger] via
 * [com.getlokalapp.paymentsdk.LokalPaymentSdk.setLogger]. Readable everywhere so any module
 * can log; settable only within :shared (`internal set`) so hosts go through that facade
 * rather than reassigning it directly.
 */
@Volatile
var Log: LokalLogger = NoOpLokalLogger
    internal set

/**
 * Default [Log] before (or after) a host installs its own — every method does nothing.
 * `internal` so [com.getlokalapp.paymentsdk.LokalPaymentSdk.setLogger] can restore it when the
 * host clears its logger, while staying invisible to host apps.
 */
internal object NoOpLokalLogger : LokalLogger {
    override fun d(forceLog: Boolean, message: () -> String) {}
    override fun i(forceLog: Boolean, message: () -> String) {}
    override fun w(forceLog: Boolean, message: () -> String) {}
    override fun e(err: Throwable?, tag: String?, forceLog: Boolean, message: () -> String) {}
}
