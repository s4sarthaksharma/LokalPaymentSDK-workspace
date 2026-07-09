package com.getlokalapp.paymentsdk.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges an opaque gateway_config/payload blob from kotlinx.serialization's
 * JsonObject to org.json.JSONObject — what every Android gateway SDK
 * (Razorpay Checkout.open(), HyperServiceHolder.initiate()/process(), ...)
 * actually takes. Shared across gateway modules so the isString fix below
 * doesn't have to be duplicated (or missed) in each one.
 */
fun JsonObject.toOrgJson(): JSONObject {
    val result = JSONObject()
    for ((key, value) in this) {
        result.put(key, value.toOrgJsonValue())
    }
    return result
}

private fun JsonElement.toOrgJsonValue(): Any = when (this) {
    is JsonNull -> JSONObject.NULL
    is JsonObject -> toOrgJson()
    is JsonArray -> JSONArray().also { array -> forEach { array.put(it.toOrgJsonValue()) } }
    is JsonPrimitive -> toOrgJsonPrimitive()
}

private fun JsonPrimitive.toOrgJsonPrimitive(): Any {
    // isString must be checked first: JsonPrimitive.longOrNull/doubleOrNull/
    // booleanOrNull parse `content` regardless of whether the original JSON
    // literal was quoted, so a numeric-looking string (e.g. a customerId of
    // "308184") would otherwise silently become a Long — some gateway SDKs
    // (e.g. Juspay's HyperSDK, error jp_003) reject that as a type mismatch.
    if (isString) return content
    booleanOrNull?.let { return it }
    longOrNull?.let { return it }
    doubleOrNull?.let { return it }
    return content
}
