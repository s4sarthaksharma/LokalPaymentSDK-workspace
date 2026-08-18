package com.getlokalapp.paymentsdk.nativeiap

import com.getlokalapp.paymentsdk.json.lenientJson
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent.PaymentResult
import com.getlokalapp.paymentsdk.testkit.assertWireKeys
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Collapsing StoreKit's richer outcome into [PaymentResult].
 *
 * The important case is [NativeIapPurchaseResult.Pending] mapping to **null**: a store purchase
 * can be deferred (Ask to Buy, SCA) and that is not a terminal state. Returning a
 * `PaymentResult` there would close the gateway flow early and strand the eventual outcome,
 * which arrives later on StoreKit's `transactionUpdates` stream.
 */
class NativeIapResultTest {

    @Test
    fun `pending is not terminal and maps to null`() {
        // If this ever returns a PaymentResult, the flow closes before the deferred purchase
        // resolves and the real outcome is lost.
        assertNull(NativeIapPurchaseResult.Pending.toPaymentResultOrNull())
    }

    @Test
    fun `success carries the ids a backend needs to verify server-side`() {
        val result = NativeIapPurchaseResult.Success(
            productId = "com.lokal.premium.monthly",
            transactionId = "2000000123456789",
            appAccountToken = "b1e0a1c2-0000-4000-8000-000000000000",
        ).toPaymentResultOrNull()

        assertIs<PaymentResult.Success>(result)
        // No signature concept for a store purchase, so the blob is just the two ids. productId
        // is deliberately absent — the backend already knows what was ordered.
        assertWireKeys(result.gatewayData, "transaction_id", "app_account_token")
        assertEquals("2000000123456789", result.gatewayData["transaction_id"]?.jsonPrimitive?.content)
        assertEquals(
            "b1e0a1c2-0000-4000-8000-000000000000",
            result.gatewayData["app_account_token"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a null app account token stays present as null`() {
        val result = NativeIapPurchaseResult.Success(
            productId = "com.lokal.premium.monthly",
            transactionId = "2000000123456789",
            appAccountToken = null,
        ).toPaymentResultOrNull()

        assertIs<PaymentResult.Success>(result)
        assertWireKeys(result.gatewayData, "transaction_id", "app_account_token")
        assertEquals(JsonNull, result.gatewayData["app_account_token"])
    }

    @Test
    fun `an unverified transaction is a failure and never a success`() {
        // StoreKit's local receipt check failed, so the purchase cannot be trusted client-side.
        val result = NativeIapPurchaseResult.Unverified(
            transactionId = "2000000123456789",
            error = "Signature mismatch",
        ).toPaymentResultOrNull()

        assertIs<PaymentResult.Failure>(result)
        assertEquals("native_iap_unverified", result.code)
        assertEquals("Signature mismatch", result.message)
    }

    @Test
    fun `an unverified transaction with no error gets a default message`() {
        val result = NativeIapPurchaseResult.Unverified(transactionId = "1", error = null).toPaymentResultOrNull()

        assertIs<PaymentResult.Failure>(result)
        assertEquals("native_iap_unverified", result.code)
        assertEquals("Transaction could not be verified", result.message)
    }

    @Test
    fun `cancellation is distinct from failure`() {
        assertEquals(
            PaymentResult.Cancelled(CancelReason.USER_DISMISSED),
            NativeIapPurchaseResult.Cancelled.toPaymentResultOrNull(),
        )
    }

    @Test
    fun `a failure carries the stable code and the vendor message`() {
        val result = NativeIapPurchaseResult.Failure(error = "Network unavailable").toPaymentResultOrNull()

        assertIs<PaymentResult.Failure>(result)
        assertEquals("native_iap_failure", result.code)
        assertEquals("Network unavailable", result.message)
    }

    @Test
    fun `a failure with no error message becomes an empty message`() {
        val result = NativeIapPurchaseResult.Failure(error = null).toPaymentResultOrNull()

        assertIs<PaymentResult.Failure>(result)
        assertEquals("", result.message)
    }

    @Test
    fun `no store failure carries a manufactured vendor blob`() {
        // There is no provider payload for a StoreKit error, so gatewayData stays null rather
        // than the SDK inventing one.
        listOf(
            NativeIapPurchaseResult.Unverified(transactionId = "1", error = "x"),
            NativeIapPurchaseResult.Failure(error = "x"),
        ).forEach { outcome ->
            val result = outcome.toPaymentResultOrNull()
            assertIs<PaymentResult.Failure>(result)
            assertNull(result.gatewayData, "$outcome manufactured a gateway blob")
        }
    }

    @Test
    fun `config decodes the store wire keys`() {
        val config = lenientJson.decodeFromString(
            NativeIapConfig.serializer(),
            """{"product_id":"com.lokal.premium.monthly","app_account_token":"b1e0a1c2","order_row_id":7}""",
        )

        assertEquals("com.lokal.premium.monthly", config.productId)
        assertEquals("b1e0a1c2", config.appAccountToken)
    }

    @Test
    fun `config app account token is optional`() {
        val config = lenientJson.decodeFromString(
            NativeIapConfig.serializer(),
            """{"product_id":"com.lokal.premium.monthly"}""",
        )

        assertNull(config.appAccountToken)
    }
}
