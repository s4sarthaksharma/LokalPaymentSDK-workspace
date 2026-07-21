package com.getlokalapp.paymentsdk.webview

/**
 * What a [WebViewSession] should load. Kept a sealed hierarchy (not a bare URL
 * string) so other load shapes a web payment flow may need — inline HTML, a
 * form POST — can be added later without changing [WebViewSession.load].
 */
sealed interface WebViewRequest {

    /** Loads [url], optionally with extra request [headers]. */
    data class Url(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : WebViewRequest
}
