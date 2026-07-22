package com.getlokalapp.paymentsdk.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

/**
 * Matches the gateway identifier the backend sends in its create-order
 * response; the host maps that code to this enum (via [fromCode]) before
 * handing the SDK a [PaymentOrder]. The code also says which shape to expect
 * in gatewayConfig. Only RAZORPAY_CHECKOUT is wired up in v1 — the rest are
 * reserved so the envelope doesn't change shape when they're added.
 *
 * Serializes by [code] (e.g. `"juspay"`) — see [GatewayStatusReport.toJson].
 */
@Serializable(with = PaymentGateway.Serializer::class)
enum class PaymentGateway(val code: String) {
    RAZORPAY_CHECKOUT("razorpay_checkout"),
    NATIVE_IAP("native_iap"),
    RAZORPAY_CUSTOM_UI("razorpay_custom_ui"),
    JUSPAY("juspay"),
    UPI_INTENT("upi_intent"),
    WEB_CHECKOUT("web_checkout");

    companion object {
        fun fromCode(code: String): PaymentGateway? = entries.firstOrNull { it.code == code }
    }

    /** Encodes/decodes by [code] (e.g. `"juspay"`), not the enum entry name. */
    object Serializer : KSerializer<PaymentGateway> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("PaymentGateway", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: PaymentGateway) =
            encoder.encodeString(value.code)

        override fun deserialize(decoder: Decoder): PaymentGateway {
            val code = decoder.decodeString()
            return fromCode(code)
                ?: throw SerializationException("Unknown gateway code: $code")
        }
    }
}

/**
 * The typed order the host hands to
 * [com.getlokalapp.paymentsdk.LokalPaymentSdk.pay]. The host calls its own
 * backend to create an order and decodes that gateway/gateway_config
 * response into this shape itself — the SDK neither makes the call nor
 * parses the response.
 *
 * gatewayConfig stays opaque here — it's only parsed once matched against
 * [gateway] (e.g. RazorpayCheckoutConfig for PaymentGateway.RAZORPAY_CHECKOUT).
 *
 * [metadata] is host-owned passthrough: the SDK never reads it and no gateway
 * ever sees it — whatever the host attaches here (its own order ref, analytics
 * tags, screen context) is echoed back verbatim on [LokalPaymentResult.metadata]
 * so the host can correlate the result to the call. `null` means none supplied.
 */
data class PaymentOrder(
    val gateway: PaymentGateway,
    val gatewayConfig: JsonObject,
    val metadata: JsonObject? = null,
)
