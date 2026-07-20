package com.getlokalapp.paymentsdk.webview

/**
 * What a [WebViewSession] should load. Kept a sealed hierarchy (not a single
 * URL string) so the same session can render a hosted page, inline HTML, or a
 * form POST — the three shapes a web-based payment flow commonly needs.
 */
sealed interface WebViewRequest {

    /** Loads [url], optionally with extra request [headers]. */
    data class Url(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : WebViewRequest

    /**
     * Renders inline [html]. [baseUrl] resolves relative links/resources and
     * sets the document origin (matters for [WebViewConfig.allowedOrigins]).
     */
    data class Html(
        val html: String,
        val baseUrl: String? = null,
    ) : WebViewRequest

    /** Issues an HTTP POST to [url] with the given [body] bytes. */
    data class Post(
        val url: String,
        val body: ByteArray,
    ) : WebViewRequest {
        // Data classes over ByteArray get identity-based equals/hashCode by
        // default, which is surprising — override to compare contents.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Post) return false
            return url == other.url && body.contentEquals(other.body)
        }

        override fun hashCode(): Int = 31 * url.hashCode() + body.contentHashCode()
    }
}
