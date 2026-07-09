package com.getlokalapp.paymentsdk.juspay

import androidx.fragment.app.FragmentActivity
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker
import com.getlokalapp.paymentsdk.json.toOrgJson
import `in`.juspay.hyperinteg.HyperServiceHolder
import `in`.juspay.hypersdk.data.JuspayResponseHandler
import `in`.juspay.hypersdk.ui.HyperPaymentsCallbackAdapter
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

internal actual fun createJuspayClient(clientId: String, tenantId: String): JuspayClient = AndroidJuspayClient()

/**
 * One persistent [HyperServiceHolder] for this client's lifetime, built
 * lazily against whichever Activity [ActivityTracker] currently
 * reports — mirrors matrimony-kmp's confirmed-working
 * `AndroidJuspayPaymentClient`, superseding this module's earlier
 * SDK-owned-proxy-Activity design. The host's Activity must be a
 * [FragmentActivity] (HyperServiceHolder's own requirement); if the
 * currently tracked Activity isn't one — or none is tracked yet — [process]
 * fails gracefully via [JuspayResultListener] rather than crashing. No
 * onBackPressed forwarding: confirmed dead code even in matrimony's own
 * shipped app.
 */
internal class AndroidJuspayClient : JuspayClient {

    @Volatile private var holder: HyperServiceHolder? = null
    @Volatile private var cachedInitPayload: JsonObject? = null
    @Volatile private var pendingProcess: JsonObject? = null
    @Volatile private var isInitiating = false
    private var listener: JuspayResultListener? = null

    override val isInitialised: Boolean get() = holder?.isInitialised == true

    private val callback = object : HyperPaymentsCallbackAdapter() {
        override fun onEvent(json: JSONObject, responseHandler: JuspayResponseHandler?) {
            when (json.optString("event")) {
                JuspayEvents.INITIATE_RESULT -> {
                    isInitiating = false
                    val pending = pendingProcess
                    if (holder?.isInitialised == true && pending != null) {
                        holder?.process(pending.toOrgJson())
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

    /** Builds the holder once, lazily, against whatever Activity is current right now. */
    private fun getHolder(): HyperServiceHolder? {
        holder?.let { return it }
        val activity = ActivityTracker.current as? FragmentActivity ?: return null
        synchronized(this) {
            holder?.let { return it }
            return HyperServiceHolder(activity).also {
                it.setCallback(callback)
                holder = it
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
        if (h.isInitialised) {
            h.process(processPayload.toOrgJson())
            return
        }
        pendingProcess = processPayload
        if (isInitiating) return
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

    override fun dispose() {
        holder?.terminate()
        holder = null
        listener = null
    }

    private fun errorData(code: String) =
        JuspayResultData(status = code, orderId = null, txnId = null, errorCode = code, errorMessage = null)
}
