package com.getlokalapp.paymentsdk.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Bridges an opaque gateway_config/payload blob from kotlinx.serialization's
 * JsonObject to a plain Kotlin Map, which Kotlin/Native bridges to
 * NSDictionary at the Objective-C boundary — what every iOS gateway SDK
 * (Razorpay Checkout.open(), HyperServices.initiate()/process(), ...)
 * actually takes. Shared across gateway modules so it doesn't have to be
 * duplicated in each one.
 */
fun JsonObject.toPlainMap(): Map<Any?, Any?> =
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
