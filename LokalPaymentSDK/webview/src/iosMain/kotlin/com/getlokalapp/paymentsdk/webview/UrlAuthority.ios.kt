package com.getlokalapp.paymentsdk.webview

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLComponents

@OptIn(ExperimentalForeignApi::class)
internal actual fun parseUrlAuthority(url: String): ParsedUrlAuthority? {
    val components = runCatching { NSURLComponents.componentsWithString(url) }
        .getOrNull()
        ?: return null
    return ParsedUrlAuthority(
        scheme = components.scheme,
        host = components.host,
        hasUserInfo = components.user != null || components.password != null,
    )
}
