package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.testkit.runGatewayTest
import com.getlokalapp.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves this module's `commonTest` source set is wired and actually runs: the
 * test compiles, executes on every target this module builds for, and can see
 * the module's own `internal` declarations — KMP makes `commonTest` a friend
 * module of `commonMain`. That friend relationship is what the rest of the test
 * plan depends on, since nearly every type in this SDK is `internal`.
 *
 * Referencing the build-generated [PAYMENT_SDK_VERSION] additionally proves the
 * generated-source wiring (`kotlin.srcDir(generatePaymentSdkVersion)`) reaches
 * the test compilation, not just `commonMain`.
 *
 * Replace this with real tests as they land; the root `check` guard requires a
 * non-empty `commonTest`. See `docs/TESTING_01_FOUNDATION_AND_GUARD.md`.
 */
class SharedHarnessSmokeTest {

    @Test
    fun `test harness runs and can see module internals`() {
        assertTrue(PAYMENT_SDK_VERSION.isNotBlank())
    }

    /**
     * Proves the shared `:test-kit` fixtures are reachable from another module's
     * `commonTest` on every target. `:shared` is the interesting case: `:test-kit` depends
     * back on `:shared`, so this also confirms that main-to-test direction resolves without
     * a project dependency cycle.
     */
    @Test
    fun `shared test-kit fixtures are usable from another module`() = runGatewayTest { logger ->
        Log.d { "routed through the kit" }

        assertEquals(listOf("routed through the kit"), logger.entries.map { it.message })
    }
}
