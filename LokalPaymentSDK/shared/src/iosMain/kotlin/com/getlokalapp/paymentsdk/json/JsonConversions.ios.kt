package com.getlokalapp.paymentsdk.json

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.JsonObject
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding

/**
 * Bridges an opaque gateway_config/payload blob from kotlinx.serialization's
 * JsonObject to the NSDictionary every iOS gateway SDK takes (Razorpay
 * Checkout.open(), HyperServices.initiate()/process(), ...). Shared across
 * gateway modules so it doesn't have to be duplicated in each one.
 *
 * Built by round-tripping the JSON text through Foundation's NSJSONSerialization
 * rather than a hand-rolled JsonElement -> Kotlin-Map walk. The hand-rolled walk
 * mis-types booleans: Kotlin/Native's Map -> NSDictionary bridging boxes a Kotlin
 * Boolean (and even an explicit NSNumber(bool = )) as a plain numeric NSNumber,
 * NOT the Foundation CFBoolean (@YES/@NO) singleton. Gateway SDKs that type-check
 * for a real boolean then reject it (Juspay HyperSDK: JP_003 "Type mismatch:
 * expected Boolean, found Number" on e.g. collectAvsInfo). NSJSONSerialization
 * produces exactly the NSString/NSNumber/CFBoolean/NSArray/NSDictionary tree a
 * native Objective-C caller would build, so booleans arrive as CFBoolean.
 */
@OptIn(ExperimentalForeignApi::class)
fun JsonObject.toPlainMap(): Map<Any?, Any?> {
    val data = (toString() as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        ?: return emptyMap()
    @Suppress("UNCHECKED_CAST")
    return NSJSONSerialization.JSONObjectWithData(data, 0uL, null) as? Map<Any?, Any?>
        ?: emptyMap()
}
