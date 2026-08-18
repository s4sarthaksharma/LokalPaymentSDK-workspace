package com.getlokalapp.paymentsdk.json

import com.getlokalapp.paymentsdk.testkit.assertWireKeys
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * [lenientJson] and [toJsonObject] are the two ends of every gateway's data path: the backend's
 * `gateway_config` is decoded with the former, and the typed result is encoded to an opaque blob
 * with the latter. Both behaviors are contracts with systems outside this codebase.
 */
class LenientJsonTest {

    @Serializable
    private data class Config(
        @SerialName("razorpay_key") val key: String,
        @SerialName("order_id") val orderId: String? = null,
    )

    @Serializable
    private data class Result(
        @SerialName("razorpay_payment_id") val paymentId: String,
        @SerialName("razorpay_order_id") val orderId: String?,
    )

    @Test
    fun `ignores unknown keys - the reason this Json exists`() {
        // Real gateway_config responses carry sibling fields a config class does not declare
        // (order_row_id and friends). Rejecting them would break payments on a backend change
        // that adds a field.
        val decoded = lenientJson.decodeFromString(
            Config.serializer(),
            """{"razorpay_key":"rzp_test","order_row_id":9182,"experiment":{"bucket":"b"}}""",
        )

        assertEquals("rzp_test", decoded.key)
    }

    @Test
    fun `still fails on a missing required field`() {
        // Leniency is about *extra* keys only — a missing required field is a real problem and
        // must surface as bad_gateway_config rather than a silent default.
        assertFails { lenientJson.decodeFromString(Config.serializer(), """{"order_id":"order_1"}""") }
    }

    @Test
    fun `still fails on a wrong value type`() {
        assertFails { lenientJson.decodeFromString(Config.serializer(), """{"razorpay_key":{"nested":1}}""") }
    }

    @Test
    fun `applies declared defaults for absent optional fields`() {
        val decoded = lenientJson.decodeFromString(Config.serializer(), """{"razorpay_key":"k"}""")

        assertNull(decoded.orderId)
    }

    @Test
    fun `toJsonObject encodes under the SerialName wire keys`() {
        // These key names are what a host forwards to its backend's verification call, so they
        // are the contract — not the Kotlin property names.
        val blob = Result(paymentId = "pay_1", orderId = "order_1").toJsonObject()

        assertWireKeys(blob, "razorpay_payment_id", "razorpay_order_id")
        assertEquals("pay_1", blob["razorpay_payment_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toJsonObject keeps a null field present rather than dropping it`() {
        // A backend reading razorpay_order_id must see an explicit null, not an absent key.
        val blob = Result(paymentId = "pay_1", orderId = null).toJsonObject()

        assertWireKeys(blob, "razorpay_payment_id", "razorpay_order_id")
        assertEquals(JsonNull, blob["razorpay_order_id"])
    }

    @Test
    fun `toJsonObject round-trips through the lenient decoder`() {
        val original = Result(paymentId = "pay_1", orderId = null)

        val decoded = lenientJson.decodeFromJsonElement(Result.serializer(), original.toJsonObject())

        assertEquals(original, decoded)
    }

    @Test
    fun `toJsonObject rejects a receiver that is not a JSON object`() {
        // Documented constraint: the receiver must serialize to an object. A primitive would
        // otherwise fail deep inside jsonObject with an unhelpful error.
        assertFails { 42.toJsonObject() }
        assertFails { "a string".toJsonObject() }
        assertFails { listOf(1, 2).toJsonObject() }
    }
}
