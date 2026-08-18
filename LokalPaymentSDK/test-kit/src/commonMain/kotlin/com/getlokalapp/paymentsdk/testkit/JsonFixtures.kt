package com.getlokalapp.paymentsdk.testkit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals

/** Builds a [JsonObject] from explicit [JsonElement] values. */
fun jsonObjectOf(vararg entries: Pair<String, JsonElement>): JsonObject =
    JsonObject(entries.toMap())

/**
 * Builds a [JsonObject] whose values are all strings — the common shape of a
 * `gateway_config` fixture, where the SDK treats the contents as opaque anyway.
 */
fun jsonOf(vararg entries: Pair<String, String>): JsonObject =
    JsonObject(entries.associate { (key, value) -> key to JsonPrimitive(value) })

/**
 * Asserts [actual] has exactly [expected] as its key set — no missing keys and no extra
 * ones.
 *
 * Use this for the blobs a gateway puts on `PaymentResult.Success`/`Pending`/`Failure`.
 * Those are **wire formats a host forwards to its own backend** (Razorpay's
 * signature-verification call, a UPI status poll), so the key names are the contract, not
 * the Kotlin property names. Asserting the exact set means a renamed `@SerialName`, a
 * dropped nullable field, or an accidentally added one all fail loudly — while an internal
 * property rename correctly does not.
 */
fun assertWireKeys(actual: JsonObject, vararg expected: String) =
    assertEquals(
        expected.toSet(),
        actual.keys,
        "Wire keys differ. Missing: ${expected.toSet() - actual.keys}; " +
            "unexpected: ${actual.keys - expected.toSet()}.",
    )
