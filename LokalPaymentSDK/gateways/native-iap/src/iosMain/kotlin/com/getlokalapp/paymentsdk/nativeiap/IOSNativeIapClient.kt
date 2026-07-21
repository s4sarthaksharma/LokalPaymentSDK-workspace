@file:OptIn(ExperimentalForeignApi::class)

package com.getlokalapp.paymentsdk.nativeiap

import cocoapods.NativeIapBridge.NativeIapBridge
import cocoapods.NativeIapBridge.NativeIapOutcomeCancelled
import cocoapods.NativeIapBridge.NativeIapOutcomeFailure
import cocoapods.NativeIapBridge.NativeIapOutcomePending
import cocoapods.NativeIapBridge.NativeIapOutcomeSuccess
import cocoapods.NativeIapBridge.NativeIapOutcomeUnverified
import cocoapods.NativeIapBridge.NativeIapResult as SwiftNativeIapResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal fun createNativeIapClient(): NativeIapClient = IOSNativeIapClient()

/**
 * Cinterops against `NativeIapBridge` (ios/NativeIapBridge/), the local pod
 * this module ships to drive StoreKit 2 from real Swift — see this module's
 * build.gradle.kts for why a direct system-framework cinterop isn't possible.
 */
internal class IOSNativeIapClient : NativeIapClient {

    override val transactionUpdates: Flow<NativeIapPurchaseResult> = callbackFlow {
        // The generated Kotlin binding types this handler's block parameter as
        // nullable even though NativeIapBridge.swift declares it non-optional —
        // Objective-C blocks don't carry the same nullability guarantees cinterop
        // otherwise enforces for plain method parameters.
        NativeIapBridge.shared.setTransactionUpdateHandler { result ->
            trySend(result.toDomainOrFailure())
        }
        awaitClose { NativeIapBridge.shared.setTransactionUpdateHandler(null) }
    }

    override suspend fun purchase(productId: String, appAccountToken: String?): NativeIapPurchaseResult {
        val deferred = CompletableDeferred<NativeIapPurchaseResult>()
        NativeIapBridge.shared.purchaseProductWithProductId(
            productId = productId,
            appAccountToken = appAccountToken,
            completion = { result -> deferred.complete(result.toDomainOrFailure()) },
        )
        return deferred.await()
    }
}

private fun SwiftNativeIapResult?.toDomainOrFailure(): NativeIapPurchaseResult =
    this?.toDomain() ?: NativeIapPurchaseResult.Failure("Null result from NativeIapBridge")

private fun SwiftNativeIapResult.toDomain(): NativeIapPurchaseResult = when (outcome) {
    NativeIapOutcomeSuccess -> NativeIapPurchaseResult.Success(
        productId = productId.orEmpty(),
        transactionId = transactionId.orEmpty(),
        appAccountToken = appAccountToken,
    )

    NativeIapOutcomeUnverified -> NativeIapPurchaseResult.Unverified(
        transactionId = transactionId.orEmpty(),
        error = errorMessage,
    )

    NativeIapOutcomeCancelled -> NativeIapPurchaseResult.Cancelled

    NativeIapOutcomePending -> NativeIapPurchaseResult.Pending

    NativeIapOutcomeFailure -> NativeIapPurchaseResult.Failure(errorMessage)

    else -> NativeIapPurchaseResult.Failure("Unknown NativeIapOutcome: $outcome")
}
