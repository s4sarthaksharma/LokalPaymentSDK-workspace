package com.getlokalapp.paymentsdk.webcheckout

import com.getlokalapp.paymentsdk.webview.TrustedWebHost
import com.getlokalapp.paymentsdk.webview.trustedWebHostOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * Validates [url] as an absolute HTTPS checkout URL and returns its normalized
 * scheme/host identity. The identity authorizes only the checkout host's pages
 * to call the native bridge; provider redirects remain navigable but cannot
 * report native payment events. Invalid input fails closed. Kept as a named
 * gateway seam so a future production/staging host allowlist can be enforced
 * here without leaking that policy into the generic `:webview` module.
 */
internal fun checkoutBridgeHost(url: String): TrustedWebHost? = trustedWebHostOf(url)
