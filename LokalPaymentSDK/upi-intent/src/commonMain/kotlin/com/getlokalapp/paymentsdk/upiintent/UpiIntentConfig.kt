package com.getlokalapp.paymentsdk.upiintent

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Parsed from PaymentOrder.gatewayConfig when gateway is
 * [com.getlokalapp.paymentsdk.model.PaymentGateway.UPI_INTENT].
 *
 * [intentUrl] is the ready-to-launch `upi://…` deep link the backend built and
 * signed (a one-time `upi://pay` or an AutoPay `upi://mandate` — this module
 * never inspects or reshapes it). [txnRef] is the merchant transaction
 * reference the host will poll its backend on; it's optional here because the
 * same value is also present in the URL's `tr` query param — see
 * [resolveTxnRef].
 */
@Serializable
internal data class UpiIntentConfig(
    @SerialName("intent_url") val intentUrl: String,
    @SerialName("txn_ref") val txnRef: String? = null,
)

/**
 * Decodes the opaque `gateway_config` blob LokalPaymentSdk already routed here.
 * `ignoreUnknownKeys` (via [lenientJson]) tolerates backend-added sibling
 * fields, per the gateway rulebook.
 */
internal fun JsonObject.toUpiIntentConfig(): UpiIntentConfig =
    lenientJson.decodeFromJsonElement(UpiIntentConfig.serializer(), this)

/**
 * The transaction reference to carry out on [com.getlokalapp.paymentsdk.model.PaymentResult.Pending]:
 * prefer the explicit [UpiIntentConfig.txnRef] sibling field, else pull the
 * UPI-spec `tr` param out of the launch URL. Falls back to "" if neither is
 * present — the host already holds its own reference from order creation, so
 * this is only a correlation convenience, never load-bearing.
 */
internal fun UpiIntentConfig.resolveTxnRef(): String =
    txnRef ?: intentUrl.upiParam("tr") ?: ""
