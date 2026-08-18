package com.getlokalapp.paymentsdk.testkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Self-tests for [RecordingLogger]. Worth having despite being test-infrastructure: every
 * later phase asserts the SDK's report-don't-throw diagnostics through this class, so a
 * false-positive assertion here would silently weaken all of them.
 */
class RecordingLoggerTest {

    @Test
    fun `records one entry per level with its evaluated message`() {
        val logger = RecordingLogger()

        logger.d { "debug" }
        logger.i { "info" }
        logger.w { "warn" }
        logger.e(err = IllegalStateException("boom"), tag = "some_tag") { "error" }
        logger.nonFatal(IllegalStateException("bad"), mapOf("code" to "a_code")) { "non-fatal" }

        assertEquals(
            listOf(
                RecordingLogger.Level.DEBUG,
                RecordingLogger.Level.INFO,
                RecordingLogger.Level.WARN,
                RecordingLogger.Level.ERROR,
                RecordingLogger.Level.NON_FATAL,
            ),
            logger.entries.map { it.level },
        )
        assertEquals(listOf("debug", "info", "warn", "error", "non-fatal"), logger.entries.map { it.message })
    }

    @Test
    fun `captures the throwable and tag passed to error`() {
        val logger = RecordingLogger()
        val cause = IllegalStateException("boom")

        logger.e(err = cause, tag = "some_tag") { "error" }

        val entry = logger.errors.single()
        assertEquals(cause, entry.throwable)
        assertEquals("some_tag", entry.tag)
    }

    @Test
    fun `assertSingleNonFatal returns the matching entry`() {
        val logger = RecordingLogger()
        logger.nonFatal(IllegalStateException("bad"), mapOf("code" to "wanted")) { "message" }
        logger.nonFatal(IllegalStateException("other"), mapOf("code" to "unwanted")) { "other" }

        val entry = logger.assertSingleNonFatal("wanted")

        assertEquals("message", entry.message)
    }

    @Test
    fun `assertSingleNonFatal fails when the code was never reported`() {
        val logger = RecordingLogger()
        logger.nonFatal(IllegalStateException("other"), mapOf("code" to "unwanted")) { "other" }

        val failure = assertFailsWith<AssertionError> { logger.assertSingleNonFatal("wanted") }

        // The message must name what was actually recorded, or a red test tells you nothing.
        assertTrue("unwanted" in (failure.message ?: ""), "Unhelpful failure: ${failure.message}")
    }

    @Test
    fun `assertSingleNonFatal fails when the same code was reported twice`() {
        val logger = RecordingLogger()
        repeat(2) { logger.nonFatal(IllegalStateException("bad"), mapOf("code" to "twice")) { "m" } }

        assertFailsWith<AssertionError> { logger.assertSingleNonFatal("twice") }
    }

    @Test
    fun `assertNoNonFatals ignores other levels but catches a non-fatal`() {
        val logger = RecordingLogger()
        logger.d { "chatter" }
        logger.e(err = null, tag = null) { "an error is not a non-fatal" }
        logger.assertNoNonFatals()

        logger.nonFatal(IllegalStateException("bad"), mapOf("code" to "c")) { "m" }
        assertFailsWith<AssertionError> { logger.assertNoNonFatals() }
    }

    @Test
    fun `assertNothingLogged catches a secret leaked in a message or an extra`() {
        val inMessage = RecordingLogger()
        inMessage.d { "signature=sig_abc123" }
        assertFailsWith<AssertionError> { inMessage.assertNothingLogged("sig_abc123") }

        val inExtra = RecordingLogger()
        inExtra.nonFatal(IllegalStateException("x"), mapOf("blob" to "sig_abc123")) { "clean" }
        assertFailsWith<AssertionError> { inExtra.assertNothingLogged("sig_abc123") }

        val clean = RecordingLogger()
        clean.d { "terminal result could not be delivered" }
        clean.assertNothingLogged("sig_abc123")
    }

    @Test
    fun `clear forgets everything recorded`() {
        val logger = RecordingLogger()
        logger.d { "before" }

        logger.clear()

        assertEquals(emptyList(), logger.entries)
    }
}
