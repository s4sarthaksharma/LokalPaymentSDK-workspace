package com.getlokalapp.paymentsdk.infrastructure

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local, one-slot ownership transfer used by SDK launchers that must
 * hand a non-Parcelable request to a proxy Activity.
 *
 * This protects only the short launcher-to-consumer handoff; it is not a lock
 * for the full payment lifetime and is not restored after process death.
 * [clearIfOwned] requires the exact same instance previously passed to
 * [tryInstall], so it cannot clear a later owner's request.
 */
class SinglePendingHandoff<T : Any> {
    private val pending = AtomicReference<T?>(null)

    /** Installs [value] only when the slot is empty; never replaces an owner. */
    fun tryInstall(value: T): Boolean = pending.compareAndSet(null, value)

    /** Atomically transfers ownership of the current value and empties the slot. */
    fun take(): T? = pending.getAndSet(null)

    /** Clears the slot only if [value] is still its exact current owner. */
    fun clearIfOwned(value: T): Boolean = pending.compareAndSet(value, null)
}

/** Stable structural error codes shared by Android proxy-Activity bridges. */
object BridgeErrorCodes {
    const val HANDOFF_IN_PROGRESS: String = "bridge_handoff_in_progress"
    const val ACTIVITY_LAUNCH_FAILED: String = "activity_launch_failed"
}
