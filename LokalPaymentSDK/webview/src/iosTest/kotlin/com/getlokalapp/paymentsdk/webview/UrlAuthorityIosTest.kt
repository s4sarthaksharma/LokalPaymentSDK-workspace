package com.getlokalapp.paymentsdk.webview

import kotlin.test.Test

/**
 * Runs the shared URL fixture table against the iOS `NSURLComponents` actual. The Android
 * half of the parity pair lives in `androidHostTest` and needs Robolectric for a real
 * `android.net.Uri` — see [assertUrlAuthorityParity] for why it cannot live in `commonTest`.
 */
class UrlAuthorityIosTest {

    @Test
    fun `url authority parity table`() = assertUrlAuthorityParity()
}
