package com.getlokalapp.paymentsdk.infrastructure

import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.getlokalapp.util.Log

/**
 * The lifecycle half of driving a [SinglePendingOperation] from a proxy Activity: *when* a started
 * operation has to be settled without a vendor result, and the guarantee it is finished cleanly either
 * way. One instance per proxy Activity instance, held as a field.
 *
 * These rules live here rather than in each gateway because they are the subtle part, and a copy that
 * drifts fails silently in opposite directions: drop the [ComponentActivity.isChangingConfigurations]
 * guard and ordinary recreations start killing live payments; narrow the destroyed-mid-operation case
 * and a destroyed payment UI wedges the host out of paying at all (see
 * `docs/IMPROVEMENT_06_DURABLE_PAYMENT_RECOVERY_CONTRACT.md`).
 *
 * Destruction is observed rather than reported: this class registers a [DefaultLifecycleObserver] on
 * [activity]'s own lifecycle, so a gateway needs no `onDestroy` override and cannot forget one. That
 * requires a [androidx.lifecycle.LifecycleOwner], which is why every proxy Activity extends
 * [ComponentActivity]. Deliberately *not* an Application-level
 * `ActivityTracker.addOnDestroyedListener`: that fires for every Activity in the process, so telling
 * ours apart needs instance identity, which would mean parking a strong reference to a live Activity in
 * process-lived state — and matching by class instead would be wrong exactly when it matters, since a
 * recreation has two instances of the same class, one dying while the other legitimately drives the
 * payment. A per-instance observer needs neither.
 *
 * A gateway supplies only what is gateway-specific: [settleWithNoResult], which reports "no result is
 * coming" to whoever is waiting — its own listener's callback, and its own decision about what an
 * unknown outcome means for that flow (a UPI hand-off already under way is Pending, a payment that
 * never started is a cancellation). It runs at most once per operation, inside
 * [SinglePendingOperation.Entry.deliverTerminalOnce], so it is also a safe place to tear down
 * gateway-owned UI.
 *
 * [diagnostics] optionally labels the non-fatal that accompanies a settle, for state only the gateway
 * knows and only it can judge the significance of — UPI Intent reports whether a UPI app already had
 * control, which is the difference between "the outcome is unknown and money may have moved" and
 * "nothing ever started". Read at report time rather than taken as a fixed map, since such state
 * changes while the payment runs. It must stay redacted: no order reference, no gateway configuration,
 * no host metadata.
 */
class OperationProxy<D : Any, L : Any>(
    private val activity: ComponentActivity,
    private val operation: SinglePendingOperation<D, L>,
    private val tag: String,
    private val diagnostics: () -> Map<String, String> = { emptyMap() },
    private val settleWithNoResult: (L) -> Unit,
) {

    private var started: SinglePendingOperation.Entry<D, L>? = null

    init {
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) = settleIfDestroyedUnsettled()
            },
        )
    }

    /**
     * The launch data this Activity should drive, or `null` when there is nothing to start — a live
     * operation whose data a predecessor instance already took (a recreation, which is settled here
     * because no vendor result can reach a new instance), or no operation at all (the process died with
     * it, taking the listener too, so there is nobody to tell). Either way the Activity is finished
     * before this returns, so a caller that gets `null` needs only to return.
     *
     * Call as the first thing in `onCreate` after `super`, and never re-launch the vendor flow on a
     * `null` — re-running it on the predecessor's order risks charging twice.
     */
    fun takeLaunchOrSettle(): D? {
        val live = operation.live()
        val launch = live?.takeData()
        if (launch == null) {
            live?.let { settle(it, HANDOFF_MISSING, "recreated with no launch data") }
            activity.finish()
            return null
        }
        started = live
        return launch
    }

    /**
     * Reports a real vendor result — success, failure, the vendor's own cancellation — exactly once,
     * and finishes. Returns whether this call is the one that delivered it: `false` means the operation
     * was already settled, typically by a recovery that ran while this result was in flight.
     */
    fun deliverTerminal(deliver: (L) -> Unit): Boolean {
        val entry = started ?: return false
        started = null
        val delivered = entry.deliverTerminalOnce(deliver)
        activity.finish()
        return delivered
    }

    /**
     * Settles the operation when the Activity was destroyed while it was still live. Runs off the
     * lifecycle observer registered in [init], so no gateway has to remember it.
     *
     * The only thing that decides whether there is anything to do is whether the operation is still
     * unsettled: [SinglePendingOperation.Entry.deliverTerminalOnce] releases the slot as it delivers, so
     * every path that already reported something — [deliverTerminal], and [takeLaunchOrSettle]'s own
     * settle — leaves nothing live to find here, and the once-only gate makes this harmless even if a
     * vendor callback is racing it.
     *
     * [ComponentActivity.isFinishing] deliberately does *not* gate this. It was once used as a proxy for
     * "something was already reported", which only holds when the Activity finishes *itself* after
     * settling: anything that finishes it from outside — a task reset from relaunching via the launcher
     * icon ([android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED] clears the stack above the task
     * root), the task being swiped away, `finishAffinity`, a host-called `finish()` — finishes an
     * Activity whose payment nobody has settled, which is precisely the case this recovery exists for.
     * Gating on it silently wedged the host out of paying until the process was restarted.
     *
     * Falls back to the installed operation when this instance never held one, so a proxy that was
     * recreated and finished immediately still cannot leave a payment unsettled.
     */
    private fun settleIfDestroyedUnsettled() {
        if (activity.isChangingConfigurations) return
        val entry = started ?: operation.live() ?: return
        settle(entry, UI_DESTROYED, "destroyed mid-operation")
    }

    private fun settle(entry: SinglePendingOperation.Entry<D, L>, code: String, trigger: String) {
        started = null
        if (!entry.deliverTerminalOnce(settleWithNoResult)) return
        Log.w { "[$tag] payment UI $trigger, reporting no result is coming" }
        // Reported as a non-fatal because a payment whose outcome nobody can determine is worth
        // counting in production - not because this is a defect: the trigger is our payment UI going
        // away, whether the system reclaimed it or something outside it finished the Activity. Without
        // it the only evidence is a logcat line nobody is watching, which is how this whole failure
        // mode went unnoticed until a hand-read device log.
        //
        // Redacted by design: a stable code, which gateway, and whatever the gateway itself labels the
        // attempt with - never an order reference, gateway configuration or host metadata (see the
        // security section of docs/IMPROVEMENT_06_DURABLE_PAYMENT_RECOVERY_CONTRACT.md).
        Log.nonFatal(
            IllegalStateException("Payment UI destroyed before any gateway result: $trigger"),
            extras = mapOf(GATEWAY to tag, CODE to code) + diagnostics(),
        ) { "[$tag] settled a payment with no gateway result" }
    }

    private companion object {
        const val GATEWAY = "gateway"
        const val CODE = "code"

        /** A proxy that was still driving a payment was destroyed, by the system or otherwise. */
        const val UI_DESTROYED = "payment_recovery_ui_destroyed"

        /** A proxy was recreated after its predecessor had already started the vendor flow. */
        const val HANDOFF_MISSING = "payment_recovery_handoff_missing"
    }
}
