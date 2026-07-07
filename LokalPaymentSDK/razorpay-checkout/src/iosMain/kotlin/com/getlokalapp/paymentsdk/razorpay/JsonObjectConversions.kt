package com.getlokalapp.paymentsdk.razorpay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Razorpay's iOS Checkout.open() takes a plain Kotlin Map, which
 * Kotlin/Native bridges to NSDictionary at the Objective-C boundary —
 * this converts the opaque gatewayConfig blob without the SDK ever
 * parsing its contents.
 */
internal fun JsonObject.toPlainMap(): Map<Any?, Any?> =
    entries.associate { (key, value) -> key to value.toPlainValue() }

private fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> toPlainMap()
    is JsonArray -> map { it.toPlainValue() }
    is JsonPrimitive -> toPlainPrimitive()
}

private fun JsonPrimitive.toPlainPrimitive(): Any? {
    if (isString) return content
    booleanOrNull?.let { return it }
    longOrNull?.let { return it }
    doubleOrNull?.let { return it }
    return content
}
