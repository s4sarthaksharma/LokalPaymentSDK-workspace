package com.getlokalapp.paymentsdk.webview

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The envelope the JS shim posts over the transport channel:
 * `{name, payload, id}`. [payload] is whatever value the page passed (any JSON);
 * [id] correlates an awaited reply.
 */
@Serializable
internal class BridgeEnvelope(
    val name: String,
    val payload: JsonElement? = null,
    val id: String? = null,
)

/**
 * Platform-agnostic core of the bridge: parses an incoming envelope string,
 * routes it to the matching [JsBridgeHandler], and builds the reply JS. The
 * only per-platform part — actually evaluating that JS in the page — is injected
 * as [evaluate]. Both Android and iOS construct one of these and feed it the raw
 * message string their transport receives.
 */
internal class BridgeDispatcher(
    config: WebViewConfig,
    private val evaluate: (String) -> Unit,
) {
    private val handlersByName: Map<String, JsBridgeHandler> = config.handlers.associateBy { it.name }

    fun dispatch(rawMessage: String) {
        val envelope = try {
            lenientJson.decodeFromString(BridgeEnvelope.serializer(), rawMessage)
        } catch (_: Exception) {
            // Malformed message from the page — ignore rather than crash the host.
            return
        }
        val handler = handlersByName[envelope.name] ?: return
        val payload = envelope.payload?.toString() ?: "null"
        val id = envelope.id
        handler.onMessage(payload) { result ->
            if (id != null) evaluate(replyScript(id, result))
        }
    }

    /**
     * JS that resolves the awaiting promise. [id] and [result] are encoded as
     * JSON primitives so any quotes/newlines are escaped safely — no manual
     * string interpolation into the page.
     */
    private fun replyScript(id: String, result: String): String =
        "window.$REPLY_FN(${JsonPrimitive(id)}, ${JsonPrimitive(result)});"
}

/**
 * Whether a bridge message from [currentUrl] is allowed given [allowed]. `null`
 * allows everything; otherwise the current URL must start with one of the
 * prefixes. Prefix match (not full origin parsing) — documented as a v1
 * simplification.
 */
internal fun isOriginAllowed(allowed: List<String>?, currentUrl: String?): Boolean {
    if (allowed == null) return true
    val url = currentUrl ?: return false
    return allowed.any { url.startsWith(it) }
}
