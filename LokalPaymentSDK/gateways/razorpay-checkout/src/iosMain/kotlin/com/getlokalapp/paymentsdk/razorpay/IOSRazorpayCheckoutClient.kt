package com.getlokalapp.paymentsdk.razorpay

import vendor.razorpay.RazorpayCheckout
import vendor.razorpay.RazorpayPaymentCompletionProtocolWithDataProtocol
import com.getlokalapp.paymentsdk.hostcontext.topmostViewController
import com.getlokalapp.paymentsdk.json.toPlainMap
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.NSObject
import platform.posix.int32_t

/**
 * Standard order-based checkout responses include razorpay_order_id /
 * razorpay_signature per Razorpay's own (cross-platform) API docs, same
 * keys used server-side for signature validation. Not verified against
 * a live sandbox in this environment — matrimony's own iOS client
 * doesn't extract these either (it stores the raw dict unparsed), so
 * treat this extraction as unverified until run against a real payment.
 */
private const val KEY_RAZORPAY_ORDER_ID = "razorpay_order_id"
private const val KEY_RAZORPAY_SIGNATURE = "razorpay_signature"

@OptIn(ExperimentalForeignApi::class)
internal actual fun createRazorpayCheckoutClient(): RazorpayCheckoutClient = IOSRazorpayCheckoutClient()

@OptIn(ExperimentalForeignApi::class)
internal class IOSRazorpayCheckoutClient : RazorpayCheckoutClient {

    private var listener: RazorpayPaymentResultListener? = null
    private var razorpay: RazorpayCheckout? = null

    private val paymentCompletionDelegate =
        object : NSObject(), RazorpayPaymentCompletionProtocolWithDataProtocol {
            override fun onPaymentSuccess(payment_id: String, andData: Map<Any?, *>?) {
                listener?.onPaymentSuccess(
                    paymentId = payment_id,
                    orderId = andData?.get(KEY_RAZORPAY_ORDER_ID) as? String,
                    signature = andData?.get(KEY_RAZORPAY_SIGNATURE) as? String ?: "",
                )
            }

            override fun onPaymentError(code: int32_t, description: String, andData: Map<Any?, *>?) {
                listener?.onPaymentError(code, description)
            }
        }

    @Suppress("UNCHECKED_CAST")
    override fun openCheckout(config: RazorpayCheckoutConfig) {
        val viewController = topmostViewController()
        if (viewController == null) {
            listener?.onPaymentError(VIEW_CONTROLLER_UNAVAILABLE_ERROR, "razorpay_no_view_controller")
            return
        }
        val instance = RazorpayCheckout.initWithKey(config.razorpayKey, andDelegateWithData = paymentCompletionDelegate)
        razorpay = instance
        // razorpay-pod's cinterop generates its own UIViewController symbol
        // (objcnames.classes.UIViewController) independent of platform.UIKit's —
        // same underlying Objective-C class at runtime, so this cast is safe.
        instance.open(
            config.data.toPlainMap(),
            displayController = viewController as objcnames.classes.UIViewController,
        )
    }

    override fun setPaymentResultListener(listener: RazorpayPaymentResultListener?) {
        this.listener = listener
    }

    private companion object {
        // Non-zero so the orchestrator classifies it as a failure, not a cancel
        // (cancel is Razorpay's own code 0).
        const val VIEW_CONTROLLER_UNAVAILABLE_ERROR = 2
    }
}
