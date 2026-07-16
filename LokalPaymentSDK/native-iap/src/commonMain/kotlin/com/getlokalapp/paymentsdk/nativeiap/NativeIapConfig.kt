package com.getlokalapp.paymentsdk.nativeiap

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Parsed from PaymentOrder.gatewayConfig when gateway is NATIVE_IAP.
 * [productId] is the store's own product identifier (App Store product id
 * today; a Play Billing SKU once Android lands) — it lives in gateway_config
 * itself specifically so this module never has to reach into the
 * order/package model the way matrimony-kmp's StoreKit integration does
 * (there, the product id comes from the app's own package mapping, not the
 * gateway response). [appAccountToken] is StoreKit 2's purchase-correlation
 * UUID; Android will likely carry a different sibling field (Play Billing's
 * obfuscated account id) rather than reusing this one.
 */
@Serializable
internal data class NativeIapConfig(
    @SerialName("product_id") val productId: String,
    @SerialName("app_account_token") val appAccountToken: String? = null,
)

/**
 * Decodes the opaque `gateway_config` blob that LokalPaymentSdk already
 * routed to this module. No gateway check is needed here — LokalPaymentSdk
 * only ever hands a NATIVE_IAP config to NativeIapSdk.
 */
internal fun JsonObject.toNativeIapConfig(): NativeIapConfig =
    lenientJson.decodeFromJsonElement(NativeIapConfig.serializer(), this)
