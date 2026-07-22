package com.getlokalapp.paymentsdk.juspay

import vendor.HyperSDK.HyperServices
import com.getlokalapp.paymentsdk.hostcontext.topmostViewController
import com.getlokalapp.paymentsdk.json.toPlainMap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.JsonObject
import platform.UIKit.UIActivityIndicatorView
import platform.UIKit.UIActivityIndicatorViewStyleLarge
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class)
internal actual fun createJuspayClient(clientId: String, tenantId: String): JuspayClient =
    IOSJuspayClient(clientId, tenantId)

/**
 * No reference in matrimony (iOS was a no-op there) — implemented against
 * the real HyperSDK.xcframework header (Hyper.h) and a live cinterop build
 * confirmed in Spike B (see docs/juspay-integration-plan.md §9 R1). Unlike
 * Android's delegate-based HyperPaymentsCallbackAdapter, HyperServices uses a
 * plain repeating callback block — mirrors AndroidJuspayClient's
 * initiate→process handshake and event mapping, adapted to that shape.
 *
 * No host-supplied UIViewController: [topmostViewController] (`:shared`'s
 * hostcontext utility) is looked up fresh whenever HyperSDK needs one,
 * instead of the host constructing and holding a handle. [initiate] never
 * shows a loader or presents anything — HyperSDK's initiate step is UI-invisible
 * (mirrors Android, where initiate() only caches the payload) — only
 * [process]'s cold-start branch (waiting on a fresh initiate before it can
 * process) shows one, since that's the only path where the user is actually
 * looking at the screen and waiting.
 *
 * ⚠️ Event/status field names below (event, payload.status, orderId,
 * epgTxnId, errorCode, errorMessage) are assumed identical to Android's —
 * Spike B did not confirm iOS emits the same keys (see R1's residual note).
 * Verify against a real sandbox transaction before shipping. The
 * connectedScenes walk below is standard practice but similarly unverified
 * against a real multi-scene/SwiftUI host — check before shipping.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IOSJuspayClient(private val clientId: String, private val tenantId: String) : JuspayClient {

    private val services = HyperServices(tenantId = tenantId, clientId = clientId)

    private var initiating = false
    private var cachedInitPayload: JsonObject? = null
    private var pendingProcess: JsonObject? = null
    private var listener: JuspayResultListener? = null
    private var loader: UIActivityIndicatorView? = null

    override val isInitialised: Boolean get() = services.isInitialised()

    override fun initiate(initPayload: JsonObject) {
        cachedInitPayload = initPayload
        startInitiate(initPayload, showLoader = false)
    }

    override fun process(processPayload: JsonObject) {
        if (services.isInitialised()) {
            services.process(processPayload.toPlainMap())
            return
        }
        pendingProcess = processPayload
        if (initiating) return
        val init = cachedInitPayload
        if (init == null) {
            pendingProcess = null
            listener?.onResult(errorData("juspay_not_initiated"))
            return
        }
        startInitiate(init, showLoader = true)
    }

    private fun startInitiate(initPayload: JsonObject, showLoader: Boolean) {
        if (services.isInitialised() || initiating) return
        val viewController = topmostViewController()
        if (viewController == null) {
            listener?.onResult(errorData("juspay_no_view_controller"))
            return
        }
        initiating = true
        if (showLoader) showLoader(viewController)
        services.initiate(viewController, initPayload.toPlainMap()) { data -> onEvent(data) }
    }

    /**
     * A plain native UIKit view (not SwiftUI — nothing here requires it, and
     * Kotlin/Native cinterop doesn't bridge SwiftUI cleanly anyway), added
     * directly onto the topmost view controller's view since HyperSDK's own
     * UI doesn't appear until it's done initiating. Juspay's own
     * `hide_loader` event is the signal to remove it.
     */
    private fun showLoader(viewController: UIViewController) {
        val view = viewController.view
        val indicator = UIActivityIndicatorView(activityIndicatorStyle = UIActivityIndicatorViewStyleLarge)
        indicator.center = view.center
        indicator.startAnimating()
        view.addSubview(indicator)
        loader = indicator
    }

    private fun hideLoader() {
        loader?.stopAnimating()
        loader?.removeFromSuperview()
        loader = null
    }

    override fun setResultListener(listener: JuspayResultListener?) {
        this.listener = listener
    }

    override fun clearResultListener(listener: JuspayResultListener) {
        if (this.listener === listener) this.listener = null
    }

    private fun onEvent(data: Map<Any?, *>?) {
        val event = data?.get("event") as? String
        when (event) {
            JuspayEvents.INITIATE_RESULT -> {
                initiating = false
                if (services.isInitialised()) {
                    pendingProcess?.let { services.process(it.toPlainMap()) }
                } else if (pendingProcess != null) {
                    listener?.onResult(errorData("initiate_failed"))
                }
                pendingProcess = null
            }

            JuspayEvents.HIDE_LOADER -> hideLoader()

            JuspayEvents.PROCESS_RESULT -> {
                @Suppress("UNCHECKED_CAST")
                val payload = data["payload"] as? Map<Any?, *>
                listener?.onResult(
                    JuspayResultData(
                        status = (payload?.get("status") as? String).orEmpty(),
                        orderId = data["orderId"] as? String ?: payload?.get("orderId") as? String,
                        txnId = data["epgTxnId"] as? String ?: payload?.get("epgTxnId") as? String,
                        errorCode = data["errorCode"] as? String,
                        errorMessage = data["errorMessage"] as? String,
                    ),
                )
            }

            else -> {
                // unhandled event
            }
        }
    }

    private fun errorData(code: String) =
        JuspayResultData(status = code, orderId = null, txnId = null, errorCode = code, errorMessage = null)
}
