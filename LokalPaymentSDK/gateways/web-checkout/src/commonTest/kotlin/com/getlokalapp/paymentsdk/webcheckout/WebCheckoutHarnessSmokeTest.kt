package com.getlokalapp.paymentsdk.webcheckout

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves this module's `commonTest` source set is wired and actually runs, can
 * see the module's own `internal` declarations (KMP makes `commonTest` a friend
 * of `commonMain`), and that the build-generated [MODULE_VERSION] reaches the
 * test compilation.
 *
 * Replace this with real tests as they land; the root `check` guard requires a
 * non-empty `commonTest`. See `docs/TESTING_01_FOUNDATION_AND_GUARD.md`.
 */
class WebCheckoutHarnessSmokeTest {

    @Test
    fun `test harness runs and can see module internals`() {
        assertTrue(MODULE_VERSION.isNotBlank())
    }
}
