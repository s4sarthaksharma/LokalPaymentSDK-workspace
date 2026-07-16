package com.getlokalapp.paymentsdk.nativeiap

import kotlinx.coroutines.flow.Flow

/**
 * Platform seam between [NativeIapSdk] and the underlying store. iOS-only for
 * now — [IOSNativeIapClient] is the only implementation until Play Billing
 * gives this an Android counterpart.
 */
internal interface NativeIapClient {

    /** Drives a single purchase attempt; may resolve [NativeIapPurchaseResult.Pending]. */
    suspend fun purchase(productId: String, appAccountToken: String?): NativeIapPurchaseResult

    /**
     * Deferred/restored transactions the store reports outside any single
     * [purchase] call (e.g. StoreKit's Ask to Buy approval, arriving later).
     */
    val transactionUpdates: Flow<NativeIapPurchaseResult>
}
