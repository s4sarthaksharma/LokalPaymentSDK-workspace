package com.getlokalapp.paymentsdk.model

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GatewayStatusReport.toJson] is a host-facing diagnostic format — it goes to logs and
 * crash-reporting backends, where the point is spotting version skew and understanding why a
 * gateway cannot pay. So the shape is asserted, not just that it serializes.
 */
class GatewayStatusReportTest {

    private val metadata = GatewayMetadata(moduleVersion = "0.0.1", vendorSdkVersion = "1.6.41")

    private fun report(
        available: List<AvailableGateway> = emptyList(),
        unavailable: List<UnavailableGateway> = emptyList(),
    ) = GatewayStatusReport(paymentSdkVersion = "0.0.1", available = available, unavailable = unavailable)

    private fun String.parsed(): JsonObject = lenientJson.parseToJsonElement(this).jsonObject

    @Test
    fun `encodes gateways by code rather than enum name`() {
        val json = report(available = listOf(AvailableGateway(PaymentGateway.JUSPAY, metadata))).toJson()

        val entry = json.parsed()["available"]!!.jsonArray.single().jsonObject
        assertEquals("juspay", entry["gateway"]!!.jsonPrimitive.content)
    }

    @Test
    fun `includes module and vendor versions so skew is visible`() {
        val json = report(
            available = listOf(AvailableGateway(PaymentGateway.RAZORPAY_CHECKOUT, metadata)),
        ).toJson()

        val meta = json.parsed()["available"]!!.jsonArray.single().jsonObject["metadata"]!!.jsonObject
        assertEquals("0.0.1", meta["moduleVersion"]!!.jsonPrimitive.content)
        assertEquals("1.6.41", meta["vendorSdkVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `carries the reason an unavailable gateway cannot pay`() {
        val json = report(
            unavailable = listOf(
                UnavailableGateway(
                    gateway = PaymentGateway.JUSPAY,
                    reasonCode = "juspay_not_initialized",
                    reasonMessage = "JuspaySdk.configure() was never called.",
                    metadata = metadata,
                ),
            ),
        ).toJson()

        val entry = json.parsed()["unavailable"]!!.jsonArray.single().jsonObject
        assertEquals("juspay_not_initialized", entry["reasonCode"]!!.jsonPrimitive.content)
        assertEquals("JuspaySdk.configure() was never called.", entry["reasonMessage"]!!.jsonPrimitive.content)
        // Metadata is present even for a gateway that cannot pay — the whole point is
        // spotting skew on a gateway that is misbehaving.
        assertTrue("metadata" in entry)
    }

    @Test
    fun `omits empty extras rather than emitting an empty object`() {
        val json = report(available = listOf(AvailableGateway(PaymentGateway.JUSPAY, metadata))).toJson()

        val meta = json.parsed()["available"]!!.jsonArray.single().jsonObject["metadata"]!!.jsonObject
        assertTrue("extras" !in meta, "Default extras should be omitted: $meta")
    }

    @Test
    fun `includes extras when a gateway supplies them`() {
        val withExtras = metadata.copy(extras = mapOf("environment" to "sandbox"))

        val json = report(available = listOf(AvailableGateway(PaymentGateway.JUSPAY, withExtras))).toJson()

        val meta = json.parsed()["available"]!!.jsonArray.single().jsonObject["metadata"]!!.jsonObject
        assertEquals("sandbox", meta["extras"]!!.jsonObject["environment"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty report emits empty arrays rather than nulls`() {
        // A host parsing this must not have to distinguish "no gateways" from "field absent".
        val parsed = report().toJson().parsed()

        assertEquals("0.0.1", parsed["paymentSdkVersion"]!!.jsonPrimitive.content)
        assertEquals(0, parsed["available"]!!.jsonArray.size)
        assertEquals(0, parsed["unavailable"]!!.jsonArray.size)
    }

    @Test
    fun `round-trips through the report serializer`() {
        val original = report(
            available = listOf(AvailableGateway(PaymentGateway.UPI_INTENT, metadata)),
            unavailable = listOf(
                UnavailableGateway(
                    PaymentGateway.RAZORPAY_CUSTOM_UI,
                    "unsupported_platform",
                    "Razorpay Custom UI is Android-only.",
                    metadata,
                ),
            ),
        )

        val decoded = lenientJson.decodeFromString(GatewayStatusReport.serializer(), original.toJson())

        assertEquals(original, decoded)
    }
}
