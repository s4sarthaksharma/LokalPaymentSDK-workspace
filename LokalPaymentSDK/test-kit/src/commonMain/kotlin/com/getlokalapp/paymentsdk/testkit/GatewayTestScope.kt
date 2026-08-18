package com.getlokalapp.paymentsdk.testkit

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Runs [body] as a coroutine test with the two things every SDK test needs set up and torn
 * down: a [RecordingLogger] installed on the SDK, and `Dispatchers.Main` bound to the
 * test's own scheduler.
 *
 * **Why Main matters.** `LokalPaymentSdk` runs every payment on
 * `CoroutineScope(SupervisorJob() + Dispatchers.Main)`. Without a Main dispatcher there is
 * none on a JVM host test and the test cannot run at all. Binding Main to `runTest`'s
 * scheduler goes further and makes the SDK's `DEFAULT_PRESENTED_DELAY_MS` (500 ms, the
 * grace period before a default `GatewayUi.Presented`) **virtual**: `advanceTimeBy(...)`
 * then controls precisely whether the UI event fires before the terminal result. That is
 * what makes the UI-pairing tests in `docs/TESTING_03_CORE_RUNTIME_CONTRACT.md` both fast
 * and deterministic, with no production change and no wall-clock waiting.
 *
 * Setup and teardown happen inside `runTest` so the logger is always restored, including
 * when [body] fails.
 *
 * ```kotlin
 * @Test
 * fun `reports a duplicate terminal result`() = runGatewayTest { logger ->
 *     // …drive the SDK…
 *     logger.assertSingleNonFatal("duplicate_terminal_result")
 * }
 * ```
 *
 * **Known gap:** this does not reset `LokalPaymentSdk`'s handler registry between tests —
 * registration is app-lifetime by design and there is no reset yet. Until decision 11a in
 * `docs/testing-plan.md` lands (`TESTING_03` Step 3), tests that register handlers can leak
 * into one another. Note the reset cannot simply be `internal` to `:shared`: this module
 * and the gateway modules' tests both need it, and `internal` reaches neither.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun runGatewayTest(body: suspend TestScope.(RecordingLogger) -> Unit): TestResult = runTest {
    val logger = RecordingLogger()
    // Restore whatever was installed before rather than unconditionally clearing to the
    // no-op logger, so one test can never strip a logger a surrounding test installed.
    val previous = Log
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    LokalPaymentSdk.setLogger(logger)
    try {
        body(logger)
    } finally {
        LokalPaymentSdk.setLogger(previous)
        Dispatchers.resetMain()
    }
}
