package com.getlokalapp.paymentsdk.nativeiap

import kotlinx.coroutines.flow.Flow

/**
 * Platform seam between [NativeIapGatewayHandler] and the underlying store. iOS-only for
 * now — [IOSNativeIapClient] is the only implementation until Play Billing
 * gives this an Android counterpart.
 */
internal interface NativeIapClient {

    /**
     * Drives a single purchase attempt; may resolve [NativeIapPurchaseResult.Pending].
     *
     * [onPresented] is invoked at most once, before this returns, when the store is about to put
     * its own payment UI on screen — the work before that point (resolving the product with the
     * store) is ours and can take seconds, so a handler turns this into
     * `GatewayUi.Presented` rather than claiming the UI is up the moment `pay()` starts. Not
     * invoked when the attempt fails before reaching the store's UI. May arrive on any thread.
     */
    suspend fun purchase(
        productId: String,
        appAccountToken: String?,
        onPresented: () -> Unit,
    ): NativeIapPurchaseResult

    /**
     * Deferred/restored transactions the store reports outside any single
     * [purchase] call (e.g. StoreKit's Ask to Buy approval, arriving later).
     */
    val transactionUpdates: Flow<NativeIapPurchaseResult>
}
