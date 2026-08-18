package com.getlokalapp.paymentsdk.testkit

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonFixturesTest {

    @Test
    fun `jsonOf builds string-valued objects`() {
        val actual = jsonOf("intent_url" to "upi://pay?tr=X", "txn_ref" to "X")

        assertEquals(JsonPrimitive("upi://pay?tr=X"), actual["intent_url"])
        assertEquals(JsonPrimitive("X"), actual["txn_ref"])
    }

    @Test
    fun `jsonObjectOf accepts non-string elements`() {
        val actual = jsonObjectOf(
            "nested" to jsonOf("a" to "b"),
            "explicit_null" to JsonNull,
        )

        assertEquals(jsonOf("a" to "b"), actual["nested"])
        assertEquals(JsonNull, actual["explicit_null"])
    }

    @Test
    fun `assertWireKeys passes on the exact key set regardless of order`() {
        val blob = jsonOf("razorpay_signature" to "s", "razorpay_payment_id" to "p")

        assertWireKeys(blob, "razorpay_payment_id", "razorpay_signature")
    }

    @Test
    fun `assertWireKeys fails on a missing key and names it`() {
        val blob = jsonOf("razorpay_payment_id" to "p")

        val failure = assertFailsWith<AssertionError> {
            assertWireKeys(blob, "razorpay_payment_id", "razorpay_signature")
        }

        assertTrue("razorpay_signature" in (failure.message ?: ""), "Unhelpful: ${failure.message}")
    }

    @Test
    fun `assertWireKeys fails on an unexpected extra key`() {
        val blob = jsonOf("razorpay_payment_id" to "p", "leaked_internal_field" to "x")

        val failure = assertFailsWith<AssertionError> {
            assertWireKeys(blob, "razorpay_payment_id")
        }

        assertTrue("leaked_internal_field" in (failure.message ?: ""), "Unhelpful: ${failure.message}")
    }

    @Test
    fun `assertWireKeys treats an explicit null value as a present key`() {
        // A nullable field such as razorpay_order_id must serialize as present-with-null,
        // not be dropped — so the key set must still contain it.
        val blob = jsonObjectOf("razorpay_order_id" to JsonNull)

        assertWireKeys(blob, "razorpay_order_id")
    }
}
