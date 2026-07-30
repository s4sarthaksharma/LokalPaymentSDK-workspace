package com.getlokalapp.paymentsdk.juspay

import vendor.HyperSDK.HyperServices
import com.getlokalapp.paymentsdk.hostcontext.topmostViewController
import com.getlokalapp.paymentsdk.json.toPlainMap
import com.getlokalapp.util.Log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.JsonObject
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSJSONSerialization
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIActivityIndicatorView
import platform.UIKit.UIActivityIndicatorViewStyleLarge
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class)
internal actual fun createJuspayClient(tenantId: String): JuspayClient = IOSJuspayClient(tenantId)

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
 * No host-supplied clientId either: [resolveClientId] reads it straight out of
 * the bundled `MerchantConfig.json` (`JuspayHostContributor` generates it from
 * the host's `juspayClientId` Gradle property, and it's already required to be
 * a target member for HyperSDK's own asset-download pipeline to work at all) —
 * mirrors Android, which resolves its clientId entirely internally too (via
 * `hypersdk.plugin`'s baked-in config), so neither platform ever takes one as
 * a Kotlin-code parameter.
 *
 * ⚠️ Event/status field names below (event, payload.status, orderId,
 * epgTxnId, errorCode, errorMessage) are assumed identical to Android's —
 * Spike B did not confirm iOS emits the same keys (see R1's residual note).
 * Verify against a real sandbox transaction before shipping. The
 * connectedScenes walk below is standard practice but similarly unverified
 * against a real multi-scene/SwiftUI host — check before shipping.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IOSJuspayClient(private val tenantId: String) : JuspayClient {

    private companion object {
        const val TAG = "IOSJuspayClient"
    }

    private val services = HyperServices(tenantId = tenantId, clientId = resolveClientId())

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
        if (initiating) {
            Log.d { "[$TAG] process() queued: initiate already in flight" }
            return
        }
        val init = cachedInitPayload
        if (init == null) {
            pendingProcess = null
            Log.w { "[$TAG] process() failed: no cached init payload" }
            Log.nonFatal(
                IllegalStateException("IOSJuspayClient.process() failed: no cached init payload"),
                extras = mapOf("gateway" to "juspay", "operation" to "process", "error_code" to "juspay_not_initiated"),
            ) { "[$TAG] no cached init payload" }
            listener?.onResult(errorData("juspay_not_initiated"))
            return
        }
        Log.d { "[$TAG] process() triggering cold-start initiate" }
        startInitiate(init, showLoader = true)
    }

    private fun startInitiate(initPayload: JsonObject, showLoader: Boolean) {
        if (services.isInitialised() || initiating) return
        val viewController = topmostViewController()
        if (viewController == null) {
            Log.w { "[$TAG] initiate failed: no topmost view controller available" }
            Log.nonFatal(
                IllegalStateException("IOSJuspayClient initiate failed: no topmost view controller available"),
                extras = mapOf("gateway" to "juspay", "operation" to "startInitiate", "error_code" to "juspay_no_view_controller"),
            ) { "[$TAG] no topmost view controller available" }
            listener?.onResult(errorData("juspay_no_view_controller"))
            return
        }
        initiating = true
        Log.d { "[$TAG] initiating HyperSDK, showLoader=$showLoader" }
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
                val initialised = services.isInitialised()
                Log.d { "[$TAG] INITIATE_RESULT, initialised=$initialised, hasPending=${pendingProcess != null}" }
                if (initialised) {
                    pendingProcess?.let { services.process(it.toPlainMap()) }
                } else if (pendingProcess != null) {
                    Log.w { "[$TAG] initiate failed while a process payload was queued" }
                    Log.nonFatal(
                        IllegalStateException("IOSJuspayClient initiate failed while a process payload was queued"),
                        extras = mapOf("gateway" to "juspay", "operation" to "onEvent:INITIATE_RESULT", "error_code" to "initiate_failed"),
                    ) { "[$TAG] initiate failed while a process payload was queued" }
                    listener?.onResult(errorData("initiate_failed"))
                }
                pendingProcess = null
            }

            JuspayEvents.HIDE_LOADER -> {
                Log.d { "[$TAG] HIDE_LOADER" }
                hideLoader()
                listener?.onUiPresented()
            }

            JuspayEvents.PROCESS_RESULT -> {
                @Suppress("UNCHECKED_CAST")
                val payload = data["payload"] as? Map<Any?, *>
                val status = (payload?.get("status") as? String).orEmpty()
                val errorCode = data["errorCode"] as? String
                Log.d { "[$TAG] PROCESS_RESULT, status=$status, errorCode=$errorCode" }
                listener?.onResult(
                    JuspayResultData(
                        status = status,
                        orderId = data["orderId"] as? String ?: payload?.get("orderId") as? String,
                        txnId = data["epgTxnId"] as? String ?: payload?.get("epgTxnId") as? String,
                        errorCode = errorCode,
                        errorMessage = data["errorMessage"] as? String,
                    ),
                )
            }

            else -> {
                Log.d { "[$TAG] unhandled event=$event" }
            }
        }
    }

    private fun errorData(code: String) =
        JuspayResultData(status = code, orderId = null, txnId = null, errorCode = code, errorMessage = null)

    /**
     * Reads the merchant clientId out of the bundled `LokalJuspayConfig.json`
     * (`{"clientId": "<client-id>"}`) — a small SDK-owned file whose schema this SDK fully
     * controls, generated by `JuspayHostContributor` from the host's `juspayClientId` Gradle
     * property at `iosApp/LokalJuspayConfig.json`. Deliberately NOT HyperSDK's own
     * `MerchantConfig.json`: that file's `clientConfigs` shape is Juspay's contract, not ours,
     * so reading it here would couple runtime clientId resolution to a schema we don't own.
     * Parses via `NSJSONSerialization` rather than round-tripping through
     * `NSString`/kotlinx.serialization, matching `JsonObject.toPlainMap`'s existing
     * Foundation-native idiom.
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveClientId(): String {
        val path = NSBundle.mainBundle.pathForResource("LokalJuspayConfig", ofType = "json")
        checkNotNull(path) {
            "LokalJuspayConfig.json not found in the app bundle. Ensure it's a member of your " +
                "app target — the Lokal Payment SDK generates it at iosApp/LokalJuspayConfig.json " +
                "from the 'juspayClientId' Gradle property."
        }
        val data = NSData.dataWithContentsOfFile(path)
        checkNotNull(data) { "Could not read LokalJuspayConfig.json at $path." }
        val json = NSJSONSerialization.JSONObjectWithData(data, 0uL, null) as? Map<Any?, Any?>
        val clientId = json?.get("clientId") as? String
        return checkNotNull(clientId) {
            "LokalJuspayConfig.json has no 'clientId' entry — check the 'juspayClientId' " +
                "Gradle property is set on your KMP module."
        }
    }
}
