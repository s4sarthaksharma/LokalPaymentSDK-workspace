package com.getlokalapp.paymentsdk.hostcontext

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * Walks the key window's presentation stack to find the topmost visible view
 * controller — looked up fresh every time a gateway needs one, instead of the
 * host constructing and holding a handle. Lives in `:shared` so every iOS
 * gateway module (Juspay, Razorpay Checkout) can present onto it the same
 * way.
 */
@OptIn(ExperimentalForeignApi::class)
fun topmostViewController(): UIViewController? {
    val keyWindow = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>() }
        .firstOrNull { it.isKeyWindow() }
    var top = keyWindow?.rootViewController
    while (top?.presentedViewController != null) {
        top = top.presentedViewController
    }
    return top
}
