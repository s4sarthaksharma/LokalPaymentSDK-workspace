package com.getlokalapp.paymentsdk.juspay

import androidx.fragment.app.FragmentActivity
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker
import com.getlokalapp.paymentsdk.json.toOrgJson
import `in`.juspay.hyperinteg.HyperServiceHolder
import `in`.juspay.hypersdk.data.JuspayResponseHandler
import `in`.juspay.hypersdk.ui.HyperPaymentsCallbackAdapter
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.lang.ref.WeakReference

internal actual fun createJuspayClient(tenantId: String): JuspayClient = AndroidJuspayClient()

/**
 * A [HyperServiceHolder] built lazily against whichever Activity
 * [ActivityTracker] currently reports, and REBUILT whenever that Activity
 * changes or dies — holders are cheap wrappers (the real HyperServices
 * engine is a static inside HyperServiceHolder, created once and kept warm
 * across holders), so rebinding costs nothing — though a rebuild triggered by
 * an Activity being destroyed terminates the engine first (see below), so a
 * genuine re-initiate follows rather than a bare reattach.
 * When the bound Activity is destroyed the client terminates the engine via
 * [HyperServiceHolder.terminate] and drops the holder — both so this
 * process-lifetime singleton doesn't pin the destroyed Activity forever
 * (matrimony-kmp's `AndroidJuspayPaymentClient`, which this otherwise
 * mirrors, has that leak) and so the static engine's `isInitialised` resets:
 * a bare `resetActivity()` leaves it `true`, so the fresh holder for the next
 * Activity would skip its real initiate() and never bind (breaks pay() after
 * a rotation that recreates the host Activity). That re-initiate is
 * self-triggered — an [ActivityTracker.addOnActivityResumedListener]
 * registration in [init] replays [cachedInitPayload] whenever an Activity of
 * class [lastBoundActivityClass] resumes, so the host never needs its own
 * `onResume()` hook for this. Scoped to onResume of that one class only — not
 * onCreate/onStart, and not any other Activity — so unrelated screens (a
 * camera picker, another SDK's own Activity, ...) never trigger it, and a
 * single recreation only fires it once (iOS has no equivalent: its
 * `HyperServices` instance is never torn down, so nothing there ever goes
 * stale the same way). The host's Activity must be a [FragmentActivity]
 * (HyperServiceHolder's own requirement); if the currently tracked Activity
 * isn't one — or none is tracked yet — [process] fails gracefully via
 * [JuspayResultListener] rather than crashing. No onBackPressed forwarding:
 * confirmed dead code even in matrimony's own shipped app.
 */
internal class AndroidJuspayClient : JuspayClient {

    @Volatile private var holder: HyperServiceHolder? = null
    @Volatile private var boundActivity: WeakReference<FragmentActivity>? = null
    @Volatile private var lastBoundActivityClass: Class<*>? = null
    @Volatile private var cachedInitPayload: JsonObject? = null
    @Volatile private var pendingProcess: JsonObject? = null
    @Volatile private var isInitiating = false
    private var listener: JuspayResultListener? = null

    init {
        ActivityTracker.addOnActivityResumedListener { activity ->
            if (boundActivity?.get() === activity) {
                cachedInitPayload?.let { doInitiate(it) }
            }
        }
        ActivityTracker.addOnDestroyedListener { destroyed ->
            synchronized(this) {
                if (boundActivity?.get() === destroyed) {
                    holder?.terminate()
                    holder = null
                    boundActivity = null
                    isInitiating = false
                    pendingProcess = null
                }
            }
        }
    }

    override val isInitialised: Boolean get() = holder?.isInitialised == true

    private val callback = object : HyperPaymentsCallbackAdapter() {
        override fun onEvent(json: JSONObject, responseHandler: JuspayResponseHandler?) {
            when (json.optString("event")) {
                JuspayEvents.INITIATE_RESULT -> {
                    isInitiating = false
                    val pending = pendingProcess
                    val h = getHolder()
                    if (h?.isInitialised == true && pending != null) {
                        h.process(pending.toOrgJson())
                    } else if (pending != null) {
                        listener?.onResult(errorData("initiate_failed"))
                    }
                    pendingProcess = null
                }

                JuspayEvents.PROCESS_RESULT -> {
                    val payload = json.optJSONObject("payload")
                    listener?.onResult(
                        JuspayResultData(
                            status = payload?.optString("status").orEmpty(),
                            orderId = json.optString("orderId").ifEmpty { payload?.optString("orderId") },
                            txnId = json.optString("epgTxnId").ifEmpty { payload?.optString("epgTxnId") },
                            errorCode = json.optString("errorCode").ifEmpty { null },
                            errorMessage = json.optString("errorMessage").ifEmpty { null },
                        ),
                    )
                }

                else -> {
                    // unhandled event (e.g. hide_loader — no dedicated loader UI on Android)
                }
            }
        }
    }

    /**
     * Returns the holder bound to the CURRENT Activity: reuses the cached
     * one only while it's still bound to the Activity the user is looking
     * at, otherwise builds and binds a fresh one. Rebinding is what keeps a
     * payment working after rotation or navigation — a holder bound to a
     * backgrounded/destroyed Activity would render HyperSDK's UI where the
     * user can't see it. Rebinds are free: holders are thin wrappers over a
     * static HyperServices engine created once, so isInitialised survives.
     * Null when no [FragmentActivity] is available (callers report a
     * graceful error instead). All holder/boundActivity access stays under
     * the same lock the destroy listener uses — no unlocked fast path.
     * Records [activity]'s class into [lastBoundActivityClass] unconditionally (even on a plain
     * cache hit) — whichever Activity is current when a Juspay op actually runs is, by this
     * class's own design, "the Juspay Activity" for self-heal purposes.
     */
    private fun getHolder(): HyperServiceHolder? {
        val activity = ActivityTracker.current as? FragmentActivity ?: return null
        lastBoundActivityClass = activity::class.java
        return synchronized(this) {
            holder?.takeIf { boundActivity?.get() === activity }
                ?: HyperServiceHolder(activity).also {
                    it.setCallback(callback)
                    holder = it
                    boundActivity = WeakReference(activity)
                }
        }
    }

    override fun initiate(initPayload: JsonObject) {
        cachedInitPayload = initPayload
        ActivityTracker.runWhenAvailable { doInitiate(initPayload) }
    }

    private fun doInitiate(initPayload: JsonObject) {
        val h = getHolder() ?: return
        if (h.isInitialised || isInitiating) return
        isInitiating = true
        h.initiate(initPayload.toOrgJson())
    }

    override fun process(processPayload: JsonObject) {
        val h = getHolder()
        if (h == null) {
            listener?.onResult(errorData("juspay_activity_unavailable"))
            return
        }
        if (isInitiating) {
            // h.isInitialised can flip true before its own INITIATE_RESULT
            // actually arrives (seen after an activity rebuild post-rotation:
            // isInitialised == true ~2s after calling initiate(), but
            // INITIATE_RESULT itself didn't fire for another ~5s) — so while
            // an initiate is in flight, always queue and let INITIATE_RESULT
            // flush it, regardless of what isInitialised claims meanwhile.
            pendingProcess = processPayload
            return
        }
        if (h.isInitialised) {
            h.process(processPayload.toOrgJson())
            return
        }
        pendingProcess = processPayload
        val init = cachedInitPayload
        if (init == null) {
            pendingProcess = null
            listener?.onResult(errorData("juspay_not_initiated"))
            return
        }
        doInitiate(init)
    }

    override fun setResultListener(listener: JuspayResultListener?) {
        this.listener = listener
    }

    override fun clearResultListener(listener: JuspayResultListener) {
        if (this.listener === listener) this.listener = null
    }

    private fun errorData(code: String) =
        JuspayResultData(status = code, orderId = null, txnId = null, errorCode = code, errorMessage = null)
}
