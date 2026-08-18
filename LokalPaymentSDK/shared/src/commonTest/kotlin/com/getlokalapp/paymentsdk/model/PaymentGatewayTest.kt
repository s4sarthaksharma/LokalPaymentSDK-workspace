package com.getlokalapp.paymentsdk.model

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PaymentGateway] is the routing key: the backend sends a code string, the host maps it via
 * [PaymentGateway.fromCode], and `LokalPaymentSdk.pay` dispatches on the result. The code is a
 * **backend wire value**, not a local identifier, so matching has to be exact and encoding has
 * to use the code rather than the enum name.
 *
 * The round-trip test iterates `entries`, so a gateway added later is covered without editing
 * this file.
 */
class PaymentGatewayTest {

    @Test
    fun `fromCode resolves every declared gateway`() {
        // Asserted per-entry rather than as a hardcoded list so a new gateway cannot be added
        // without its code being resolvable.
        PaymentGateway.entries.forEach { gateway ->
            assertEquals(gateway, PaymentGateway.fromCode(gateway.code), "code=${gateway.code}")
        }
    }

    @Test
    fun `codes are the documented backend values`() {
        // Pinned literally: these strings are a contract with the backend, so a rename here
        // must be a deliberate, visible change rather than a refactor side effect.
        assertEquals("razorpay_checkout", PaymentGateway.RAZORPAY_CHECKOUT.code)
        assertEquals("native_iap", PaymentGateway.NATIVE_IAP.code)
        assertEquals("razorpay_custom_ui", PaymentGateway.RAZORPAY_CUSTOM_UI.code)
        assertEquals("juspay", PaymentGateway.JUSPAY.code)
        assertEquals("upi_intent", PaymentGateway.UPI_INTENT.code)
        assertEquals("web_checkout", PaymentGateway.WEB_CHECKOUT.code)
    }

    @Test
    fun `codes are unique`() {
        val codes = PaymentGateway.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size, "Duplicate gateway codes: $codes")
    }

    @Test
    fun `fromCode returns null for an unknown code`() {
        assertNull(PaymentGateway.fromCode("stripe"))
        assertNull(PaymentGateway.fromCode(""))
        assertNull(PaymentGateway.fromCode("   "))
    }

    @Test
    fun `fromCode is case sensitive`() {
        // The backend supplies this value, so tolerating case would silently accept a
        // malformed response rather than surfacing it.
        assertNull(PaymentGateway.fromCode("JUSPAY"))
        assertNull(PaymentGateway.fromCode("Juspay"))
    }

    @Test
    fun `fromCode does not match the enum entry name`() {
        // RAZORPAY_CHECKOUT is the Kotlin name; razorpay_checkout is the wire value.
        assertNull(PaymentGateway.fromCode("RAZORPAY_CHECKOUT"))
    }

    @Test
    fun `serializes to the code rather than the enum name`() {
        // This is what GatewayStatusReport.toJson ships to a host's diagnostics.
        PaymentGateway.entries.forEach { gateway ->
            val encoded = lenientJson.encodeToString(PaymentGateway.Serializer, gateway)

            assertEquals("\"${gateway.code}\"", encoded)
        }
    }

    @Test
    fun `deserializes from the code`() {
        PaymentGateway.entries.forEach { gateway ->
            val decoded = lenientJson.decodeFromString(
                PaymentGateway.Serializer,
                JsonPrimitive(gateway.code).toString(),
            )

            assertEquals(gateway, decoded)
        }
    }

    @Test
    fun `deserializing an unknown code fails loudly`() {
        val failure = assertFailsWith<SerializationException> {
            lenientJson.decodeFromString(PaymentGateway.Serializer, "\"stripe\"")
        }

        assertTrue("stripe" in (failure.message ?: ""), "Unhelpful message: ${failure.message}")
    }

    @Test
    fun `deserializing the enum name fails`() {
        assertFailsWith<SerializationException> {
            lenientJson.decodeFromString(PaymentGateway.Serializer, "\"JUSPAY\"")
        }
    }

    @Test
    fun `round-trips every gateway`() {
        PaymentGateway.entries.forEach { gateway ->
            val encoded = lenientJson.encodeToString(PaymentGateway.Serializer, gateway)
            val decoded = lenientJson.decodeFromString(PaymentGateway.Serializer, encoded)

            assertEquals(gateway, decoded)
        }
    }
}
