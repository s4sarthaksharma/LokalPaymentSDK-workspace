package com.getlokalapp.paymentsdk.razorpay

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
 * Razorpay's Android Checkout.open() takes org.json.JSONObject, not
 * kotlinx. Serialization's JsonObject — this bridges the opaque
 * gatewayConfig blob across without the SDK ever parsing its contents.
 */
internal fun JsonObject.toOrgJson(): JSONObject {
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
    booleanOrNull?.let { return it }
    longOrNull?.let { return it }
    doubleOrNull?.let { return it }
    return content
}
