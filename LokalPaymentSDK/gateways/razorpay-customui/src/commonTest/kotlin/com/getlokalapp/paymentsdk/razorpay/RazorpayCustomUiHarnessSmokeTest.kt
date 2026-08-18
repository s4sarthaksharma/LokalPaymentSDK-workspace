package com.getlokalapp.paymentsdk.razorpay

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves this module's `commonTest` source set is wired and actually runs, can
 * see the module's own `internal` declarations (KMP makes `commonTest` a friend
 * of `commonMain`), and that the build-generated [MODULE_VERSION] reaches the
 * test compilation.
 *
 * Class name is module-prefixed on purpose: `:razorpay-customui` and
 * `:razorpay-checkout` share the package `com.getlokalapp.paymentsdk.razorpay`,
 * so identically named top-level files in both would collide — see
 * `docs/adding-a-new-gateway.md` §5 rule 9. Note this resolves to *this*
 * module's own `internal` constant, not the checkout module's.
 *
 * Replace this with real tests as they land; the root `check` guard requires a
 * non-empty `commonTest`. See `docs/TESTING_01_FOUNDATION_AND_GUARD.md`.
 */
class RazorpayCustomUiHarnessSmokeTest {

    @Test
    fun `test harness runs and can see module internals`() {
        assertTrue(MODULE_VERSION.isNotBlank())
    }
}
