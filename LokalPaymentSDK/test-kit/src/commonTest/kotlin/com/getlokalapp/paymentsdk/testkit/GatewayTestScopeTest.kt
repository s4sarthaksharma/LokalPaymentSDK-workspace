package com.getlokalapp.paymentsdk.testkit

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Self-tests for [runGatewayTest]. The virtual-time test below is the important one: the
 * SDK's 500 ms `GatewayUi.Presented` grace period is only testable — and every UI-pairing
 * test in `docs/TESTING_03_CORE_RUNTIME_CONTRACT.md` is only deterministic — if
 * `Dispatchers.Main` really is bound to this test's scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayTestScopeTest {

    @Test
    fun `installs the recording logger for the duration of the test`() = runGatewayTest { logger ->
        Log.d { "from inside the test" }

        assertEquals(listOf("from inside the test"), logger.entries.map { it.message })
    }

    @Test
    fun `binds Main to the test scheduler so delays are virtual`() = runGatewayTest {
        var fired = false
        CoroutineScope(Dispatchers.Main).launch {
            delay(500)
            fired = true
        }

        // StandardTestDispatcher queues rather than running eagerly, so nothing has run yet.
        assertFalse(fired, "Coroutine ran before the scheduler was advanced")

        advanceTimeBy(499)
        assertFalse(fired, "500 ms delay completed early")

        advanceTimeBy(2)
        assertTrue(fired, "500 ms delay never completed in virtual time")
    }

    @Test
    fun `restores the previously installed logger afterwards`() {
        val outer = RecordingLogger()
        LokalPaymentSdk.setLogger(outer)
        try {
            runGatewayTest { inner ->
                Log.d { "inner" }
                assertEquals(1, inner.entries.size)
            }

            // Back outside, the outer logger must be receiving again — otherwise one test
            // could silently strip the logger a surrounding test installed.
            Log.d { "outer again" }
            assertEquals(listOf("outer again"), outer.entries.map { it.message })
        } finally {
            LokalPaymentSdk.setLogger(null)
        }
    }

    @Test
    fun `restores the logger even when the test body fails`() {
        val outer = RecordingLogger()
        LokalPaymentSdk.setLogger(outer)
        try {
            val failed = runCatching {
                runGatewayTest { throw IllegalStateException("deliberate failure") }
            }.isFailure
            assertTrue(failed, "Expected the failing body to propagate")

            Log.d { "outer again" }
            assertEquals(listOf("outer again"), outer.entries.map { it.message })
        } finally {
            LokalPaymentSdk.setLogger(null)
        }
    }
}
