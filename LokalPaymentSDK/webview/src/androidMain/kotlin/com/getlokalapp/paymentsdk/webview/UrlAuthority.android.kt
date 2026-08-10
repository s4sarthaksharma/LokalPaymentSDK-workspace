package com.getlokalapp.paymentsdk.webview

import android.net.Uri

internal actual fun parseUrlAuthority(url: String): ParsedUrlAuthority? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    return ParsedUrlAuthority(
        scheme = uri.scheme,
        host = uri.host,
        hasUserInfo = uri.userInfo != null,
    )
}
