package com.getlokalapp.paymentsdk.testkit

import com.getlokalapp.util.LokalLogger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A [LokalLogger] that records everything it receives, so tests can assert on the SDK's
 * diagnostics.
 *
 * This matters more here than in a typical codebase: LokalPaymentSDK deliberately
 * **reports rather than throws** on contract violations — a duplicate terminal result, an
 * unparseable `gateway_config`, gateway cleanup that fails, a flow that completes with no
 * terminal event. Those paths are the SDK's safety net, they are invisible to a caller,
 * and nothing verified them before this. Asserting on the recorded diagnostics is the only
 * way to prove they fire.
 *
 * Install it via [runGatewayTest], which also restores the previous logger afterwards.
 *
 * Not thread-safe: entries go into a plain list. Tests drive the SDK through
 * `runGatewayTest`'s single-threaded test dispatcher, so this is sufficient; a test that
 * deliberately logs from several threads must synchronize its own assertions.
 */
class RecordingLogger : LokalLogger {

    /** Which [LokalLogger] method produced an [Entry]. */
    enum class Level { DEBUG, INFO, WARN, ERROR, NON_FATAL }

    /**
     * One recorded call. [message] is the *evaluated* lambda — evaluating it is itself part
     * of the behavior under test, since the no-op logger must never evaluate it (see
     * `IMPROVEMENT_02`: that is what keeps full payment payloads out of release builds).
     */
    data class Entry(
        val level: Level,
        val message: String,
        val throwable: Throwable? = null,
        val tag: String? = null,
        val extras: Map<String, String> = emptyMap(),
    )

    private val recorded = mutableListOf<Entry>()

    /** Everything recorded so far, oldest first. */
    val entries: List<Entry> get() = recorded.toList()

    /** Only the [Level.NON_FATAL] entries — the SDK's contract-violation reports. */
    val nonFatals: List<Entry> get() = recorded.filter { it.level == Level.NON_FATAL }

    /** Only the [Level.ERROR] entries. */
    val errors: List<Entry> get() = recorded.filter { it.level == Level.ERROR }

    override fun d(forceLog: Boolean, message: () -> String) =
        record(Level.DEBUG, message())

    override fun i(forceLog: Boolean, message: () -> String) =
        record(Level.INFO, message())

    override fun w(forceLog: Boolean, message: () -> String) =
        record(Level.WARN, message())

    override fun e(err: Throwable?, tag: String?, forceLog: Boolean, message: () -> String) =
        record(Level.ERROR, message(), throwable = err, tag = tag)

    override fun nonFatal(
        throwable: Throwable,
        extras: Map<String, String>,
        message: () -> String,
    ) = record(Level.NON_FATAL, message(), throwable = throwable, extras = extras)

    private fun record(
        level: Level,
        message: String,
        throwable: Throwable? = null,
        tag: String? = null,
        extras: Map<String, String> = emptyMap(),
    ) {
        recorded += Entry(level, message, throwable, tag, extras)
    }

    /** Forgets everything recorded so far. */
    fun clear() = recorded.clear()

    // ---------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------

    /**
     * Asserts exactly one non-fatal was reported carrying `extras["code"] == `[code], and
     * returns it. The SDK's stable diagnostic codes (`bad_gateway_config`,
     * `duplicate_terminal_result`, `gateway_cleanup_failed`,
     * `terminal_delivery_failed`, `gateway_flow_completed_without_result`) are all
     * reported this way, and "exactly one" is usually the point — a duplicate report is
     * as wrong as a missing one.
     */
    fun assertSingleNonFatal(code: String): Entry {
        val matching = nonFatals.filter { it.extras["code"] == code }
        if (matching.size != 1) {
            fail(
                "Expected exactly 1 non-fatal with code=\"$code\", found ${matching.size}." +
                    describeNonFatals(),
            )
        }
        return matching.single()
    }

    /** Asserts no non-fatal was reported at all — nothing violated a contract. */
    fun assertNoNonFatals() {
        if (nonFatals.isNotEmpty()) {
            fail("Expected no non-fatals, found ${nonFatals.size}." + describeNonFatals())
        }
    }

    /**
     * Asserts [secret] appears in **nothing** that was logged — no message, no extra value.
     *
     * For the deliberate confidentiality boundaries in the SDK. `GatewayResultScope`'s
     * undeliverable-terminal path carries an explicit comment that the result must not be
     * logged there, because `gatewayData` can hold signatures and transaction references;
     * that rule has never been enforced by a test. Note this is narrower than
     * `IMPROVEMENT_02`, which *intends* full payloads to reach a host-installed logger —
     * use this only for the paths documented as redacted.
     */
    fun assertNothingLogged(secret: String) {
        val leaked = recorded.filter { entry ->
            secret in entry.message || entry.extras.values.any { secret in it }
        }
        assertTrue(
            leaked.isEmpty(),
            "Expected \"$secret\" never to be logged, but ${leaked.size} entry/entries " +
                "contained it: ${leaked.joinToString { "${it.level}: ${it.message}" }}",
        )
    }

    /** Asserts the total number of recorded entries, for tests pinning log volume. */
    fun assertEntryCount(expected: Int) =
        assertEquals(expected, recorded.size, "Recorded entries: ${recorded.map { it.level }}")

    private fun describeNonFatals(): String =
        if (nonFatals.isEmpty()) {
            " No non-fatals were recorded at all."
        } else {
            nonFatals.joinToString(prefix = " Recorded non-fatals: ") { entry ->
                "[code=${entry.extras["code"]}, message=${entry.message}]"
            }
        }
}
