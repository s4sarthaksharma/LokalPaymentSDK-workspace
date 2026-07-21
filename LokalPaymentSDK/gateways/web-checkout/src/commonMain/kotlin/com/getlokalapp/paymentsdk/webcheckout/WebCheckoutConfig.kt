package com.getlokalapp.paymentsdk.webcheckout

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Parsed from PaymentOrder.gatewayConfig when gateway is
 * [com.getlokalapp.paymentsdk.model.PaymentGateway.WEB_CHECKOUT].
 *
 * [gatewayUrl] is the fully-built, ready-to-open hosted-gateway URL the backend
 * assembled — checkoutUrl-encoding, provider, and base domain are all decided
 * upstream (see docs/web-checkout-gateway-plan.md). The SDK opens it verbatim
 * and never assembles URLs or holds environment domains. The provider
 * (dodo/stripe/…) is opaque, encoded inside this URL by the backend.
 *
 * NOTE: the field name `gateway_url` is assumed pending backend confirmation —
 * the single place to change if the backend names it differently.
 */
@Serializable
internal data class WebCheckoutConfig(
    @SerialName("gateway_url") val gatewayUrl: String,
)

/**
 * Decodes the opaque `gateway_config` blob LokalPaymentSdk already routed here.
 * `ignoreUnknownKeys` (via [lenientJson]) tolerates backend-added sibling fields,
 * per the gateway rulebook.
 */
internal fun JsonObject.toWebCheckoutConfig(): WebCheckoutConfig =
    lenientJson.decodeFromJsonElement(WebCheckoutConfig.serializer(), this)

/**
 * The scheme+host(+port) prefix of [url], used as the bridge's allowed origin so
 * only the gateway's own pages (`/callback`, `/cancel`, `/pay`) may post events —
 * never the provider's checkout domain. Prefix match, matching `:webview`'s
 * origin gating. Returns null when [url] has no scheme (allow-all fallback).
 */
internal fun originOf(url: String): String? {
    val schemeSep = url.indexOf("://")
    if (schemeSep < 0) return null
    val pathStart = url.indexOf('/', startIndex = schemeSep + 3)
    return if (pathStart < 0) url else url.substring(0, pathStart)
}
