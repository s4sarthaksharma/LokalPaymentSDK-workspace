package com.getlokalapp.paymentsdk.infrastructure

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local record of the one operation an SDK launcher has handed to a proxy Activity. It holds
 * the two things a handoff needs, which have two *different* lifetimes — conflating them is what
 * makes a payment unsettleable:
 *
 * - **[D] launch data** — the non-Parcelable payload the proxy needs to *start* (vendor key, order
 *   JSON, intent URL). Transferred exactly once through [Entry.takeData] and dropped, so a payment's
 *   configuration is not kept in memory for the minutes a user may spend in another app.
 * - **[L] listener** — who is waiting for the *result*. Retained until the operation is settled,
 *   because it is the only route back to the suspended gateway flow. An Activity instance must not
 *   own it: the system can destroy that instance while the process — and the waiting flow — lives on,
 *   and a payment whose listener died with its Activity is one no vendor callback can ever settle.
 *
 * The states a caller distinguishes:
 *
 * | [live] | [Entry.takeData] | meaning |
 * | --- | --- | --- |
 * | `null` | — | nothing in flight; or the process died with it, taking the listener too |
 * | entry | data | a launch waiting for its proxy to pick it up |
 * | entry | `null` | **an operation in flight** — a proxy instance already took the data |
 *
 * A proxy Activity that finds a live entry with no data left to take is therefore a recreation whose
 * predecessor already started the vendor flow. No result can arrive in that state, since vendors
 * report only to the exact instance that launched them, so settling it through
 * [Entry.deliverTerminalOnce] is the only way the host ever hears back.
 *
 * Unlike the launch-only slot this replaces, the entry stays installed until it is settled, so
 * [tryInstall] refusing means *a payment* is in flight rather than merely a launch.
 *
 * Nothing here survives process death, by design — see
 * `docs/IMPROVEMENT_06_DURABLE_PAYMENT_RECOVERY_CONTRACT.md`: the listener is process-local, so after
 * a restart there is nobody left to tell.
 */
class SinglePendingOperation<D : Any, L : Any> {

    private val current = AtomicReference<Entry<D, L>?>(null)

    /**
     * One installed operation: its launch data until someone takes it, its listener until someone
     * settles it, and the guarantee that exactly one terminal is ever delivered.
     */
    class Entry<D : Any, L : Any> internal constructor(
        private val owner: SinglePendingOperation<D, L>,
        data: D,
        private val listener: L?,
    ) {

        private val pendingData = AtomicReference<D?>(data)
        private val terminalDelivered = AtomicBoolean(false)

        /**
         * Transfers the launch data to the caller, exactly once for this operation's lifetime, and
         * releases this object's reference to it. `null` means a previous Activity instance already
         * took it — this one is a recreation and has nothing to start.
         */
        fun takeData(): D? = pendingData.getAndSet(null)

        /**
         * Hands this operation's one and only terminal to [deliver] and releases the slot, returning
         * whether it was this call that delivered it. Every later call is a no-op, so a real vendor
         * callback and a lifecycle-driven recovery can race harmlessly: the loser is dropped rather
         * than producing a second terminal for an operation the SDK has already settled.
         */
        fun deliverTerminalOnce(deliver: (L) -> Unit): Boolean {
            if (!terminalDelivered.compareAndSet(false, true)) return false
            owner.current.compareAndSet(this, null)
            listener?.let(deliver)
            return true
        }
    }

    /**
     * Installs [data] + [listener] as the operation in flight, returning the entry to drive it, or
     * `null` when one is already in flight and this launch must be refused.
     */
    fun tryInstall(data: D, listener: L?): Entry<D, L>? {
        val entry = Entry(this, data, listener)
        return if (current.compareAndSet(null, entry)) entry else null
    }

    /** The operation still awaiting a terminal, if any. */
    fun live(): Entry<D, L>? = current.get()

    /**
     * Releases the slot without delivering anything, and only if [entry] is still its current owner —
     * for a launcher abandoning a launch that never reached its proxy. Settling a *started* operation
     * goes through [Entry.deliverTerminalOnce] instead, so its listener is not left waiting.
     */
    fun clearIfOwned(entry: Entry<D, L>): Boolean = current.compareAndSet(entry, null)
}

/** Stable structural error codes shared by Android proxy-Activity bridges. */
object BridgeErrorCodes {
    const val HANDOFF_IN_PROGRESS: String = "bridge_handoff_in_progress"
    const val ACTIVITY_LAUNCH_FAILED: String = "activity_launch_failed"
}
