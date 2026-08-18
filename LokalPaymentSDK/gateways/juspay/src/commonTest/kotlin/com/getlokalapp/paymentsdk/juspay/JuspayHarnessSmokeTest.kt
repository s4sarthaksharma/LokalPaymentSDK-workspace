package com.getlokalapp.paymentsdk.juspay

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves this module's `commonTest` source set is wired and actually runs, can
 * see the module's own `internal` declarations (KMP makes `commonTest` a friend
 * of `commonMain`), and that the build-generated [MODULE_VERSION] reaches the
 * test compilation.
 *
 * This module is the reason the shared test convention exists: before
 * `paymentSdkTestConventions()`, `:juspay` carried no `commonTest` dependency
 * block at all and therefore could not host a test — the gap went unnoticed
 * because an absent test source set reports `NO-SOURCE`, which passes.
 *
 * Replace this with real tests as they land; the root `check` guard requires a
 * non-empty `commonTest`. See `docs/TESTING_01_FOUNDATION_AND_GUARD.md`.
 */
class JuspayHarnessSmokeTest {

    @Test
    fun `test harness runs and can see module internals`() {
        assertTrue(MODULE_VERSION.isNotBlank())
    }
}
