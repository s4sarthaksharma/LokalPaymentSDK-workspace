package com.getlokalapp.paymentsdk.webview

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves this module's `commonTest` source set is wired and actually runs, and
 * that it can see the module's own `internal` declarations — KMP makes
 * `commonTest` a friend module of `commonMain`, which is what the rest of the
 * test plan depends on since nearly every type here is `internal`.
 *
 * Unlike the gateway modules, `:webview` generates no version constant, so this
 * references [TRANSPORT_NAME] — the bridge's fixed native transport channel.
 *
 * Replace this with real tests as they land; the root `check` guard requires a
 * non-empty `commonTest`. See `docs/TESTING_01_FOUNDATION_AND_GUARD.md`.
 */
class WebViewHarnessSmokeTest {

    @Test
    fun `test harness runs and can see module internals`() {
        assertTrue(TRANSPORT_NAME.isNotBlank())
    }
}
