@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.getlokalapp.paymentsdk.webview

import com.getlokalapp.paymentsdk.hostcontext.topmostViewController
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setValue
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIColor
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

actual fun createWebViewSession(config: WebViewConfig): WebViewSession = IosWebViewSession(config)

/**
 * Drives a `WKWebView` on iOS by presenting [WebViewController] onto
 * [topmostViewController] — the same lookup-fresh presentation idiom as
 * `:upi-intent`'s IosUpiIntentClient, so the host never supplies or receives a
 * view controller. Runs on the caller's thread; UIKit requires main.
 */
internal class IosWebViewSession(private val config: WebViewConfig) : WebViewSession {

    private var controller: WebViewController? = null

    override fun load(request: WebViewRequest) {
        val existing = controller
        if (existing != null) {
            existing.loadRequest(request)
            return
        }
        val presenter = topmostViewController()
        if (presenter == null) {
            config.listener?.onError(ERROR_NO_VIEW_CONTROLLER, "webview_no_view_controller")
            return
        }
        val created = WebViewController(config) { controller = null }
        created.setModalPresentationStyle(UIModalPresentationFullScreen)
        controller = created
        presenter.presentViewController(created, animated = true) { created.loadRequest(request) }
    }

    override fun evaluateJavascript(script: String, onResult: ((String?) -> Unit)?) {
        controller?.evaluateJs(script, onResult)
    }

    override fun close() {
        controller?.dismissViewControllerAnimated(true, null)
    }

    private companion object {
        const val ERROR_NO_VIEW_CONTROLLER = "no_view_controller"
    }
}

/**
 * Full-screen `WKWebView` host. Conforms to [WKScriptMessageHandlerProtocol]
 * (the transport channel) and [WKNavigationDelegateProtocol] (page lifecycle /
 * redirect interception). The bridge shim is injected as a document-start
 * [WKUserScript].
 *
 * The message handler is registered through a [WeakScriptMessageProxy] so the
 * `WKUserContentController` (owned, transitively, by this controller) does NOT
 * strongly retain the controller back — otherwise `controller → webView →
 * configuration → userContentController → handler → controller` would be an
 * ObjC retain cycle that survives even if teardown never runs. With the weak
 * proxy the cycle can't form; [viewDidDisappear] still removes the handler as
 * prompt cleanup, but correctness no longer depends on it firing. UIKit retains
 * the controller while presented, so no extra strong reference is needed.
 */
private class WebViewController(
    private val config: WebViewConfig,
    private val onClosed: () -> Unit,
) : UIViewController(nibName = null, bundle = null),
    WKScriptMessageHandlerProtocol,
    WKNavigationDelegateProtocol {

    private var webView: WKWebView? = null
    private var dispatcher: BridgeDispatcher? = null
    private var handlerAttached = false

    override fun viewDidLoad() {
        super.viewDidLoad()
        val root = view ?: return

        val contentController = WKUserContentController()
        contentController.addUserScript(
            WKUserScript(
                source = iosBridgeShim(config.bridgeName),
                injectionTime = WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
            ),
        )
        config.userScripts.forEach { script ->
            contentController.addUserScript(
                WKUserScript(
                    source = script,
                    injectionTime = WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true,
                ),
            )
        }
        contentController.addScriptMessageHandler(WeakScriptMessageProxy(this), name = TRANSPORT_NAME)
        handlerAttached = true

        val configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController

        val wv = WKWebView(frame = root.bounds, configuration = configuration)
        wv.navigationDelegate = this
        // Pin to the safe-area layout guide (not raw bounds) so no content sits
        // under the status bar / notch / home indicator. The margins show the
        // controller's background.
        root.setBackgroundColor(UIColor.whiteColor)
        wv.setTranslatesAutoresizingMaskIntoConstraints(false)
        root.addSubview(wv)
        val safeArea = root.safeAreaLayoutGuide
        NSLayoutConstraint.activateConstraints(
            listOf(
                wv.topAnchor.constraintEqualToAnchor(safeArea.topAnchor),
                wv.leadingAnchor.constraintEqualToAnchor(safeArea.leadingAnchor),
                wv.trailingAnchor.constraintEqualToAnchor(safeArea.trailingAnchor),
                wv.bottomAnchor.constraintEqualToAnchor(safeArea.bottomAnchor),
            ),
        )
        webView = wv
        // Reply evaluation always on main (this delegate/handler already runs there).
        dispatcher = BridgeDispatcher(config) { script -> wv.evaluateJavaScript(script, null) }
    }

    fun loadRequest(request: WebViewRequest) {
        val wv = webView ?: return
        when (request) {
            is WebViewRequest.Url -> {
                val urlRequest = NSMutableURLRequest(uRL = NSURL(string = request.url))
                request.headers.forEach { (key, value) -> urlRequest.setValue(value, forHTTPHeaderField = key) }
                wv.loadRequest(urlRequest)
            }
        }
    }

    fun evaluateJs(script: String, onResult: ((String?) -> Unit)?) {
        webView?.evaluateJavaScript(script) { result, _ -> onResult?.invoke(result?.toString()) }
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        if (didReceiveScriptMessage.name != TRANSPORT_NAME) return
        if (!isOriginAllowed(config.allowedOrigins, webView?.URL?.absoluteString)) return
        val body = didReceiveScriptMessage.body as? String ?: return
        dispatcher?.dispatch(body)
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        val intercept = url != null && (config.listener?.onNavigation(url) ?: false)
        decisionHandler(
            if (intercept) WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            else WKNavigationActionPolicy.WKNavigationActionPolicyAllow,
        )
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        config.listener?.onPageStarted(webView.URL?.absoluteString.orEmpty())
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        config.listener?.onPageFinished(webView.URL?.absoluteString.orEmpty())
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        // Only when this controller is actually going away — viewDidDisappear
        // also fires when another VC is presented on top of it.
        if (!isBeingDismissed() && !isMovingFromParentViewController()) return
        if (handlerAttached) {
            webView?.configuration?.userContentController?.removeScriptMessageHandlerForName(TRANSPORT_NAME)
            handlerAttached = false
        }
        webView?.navigationDelegate = null
        onClosed()
        config.listener?.onClosed()
    }
}

/**
 * Weakly forwards `WKScriptMessage`s to [handler]. `WKUserContentController`
 * strongly retains whatever is passed to `addScriptMessageHandler`, so passing
 * the controller directly would close an ObjC retain cycle back onto it; this
 * proxy holds the controller weakly instead, so the cycle can't form. Once the
 * real handler is gone the forward is simply a no-op.
 */
private class WeakScriptMessageProxy(
    handler: WKScriptMessageHandlerProtocol,
) : NSObject(), WKScriptMessageHandlerProtocol {

    private val handler = WeakReference(handler)

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        handler.get()?.userContentController(userContentController, didReceiveScriptMessage)
    }
}
