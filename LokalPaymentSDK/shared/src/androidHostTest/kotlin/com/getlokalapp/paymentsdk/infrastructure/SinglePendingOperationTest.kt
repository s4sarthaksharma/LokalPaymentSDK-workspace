package com.getlokalapp.paymentsdk.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every Android proxy-Activity gateway routes its exactly-once terminal guarantee through this class,
 * so the invariants asserted here are the ones standing between a destroyed payment UI and either a
 * host locked out of all future payments (no terminal at all) or a payment reported twice.
 *
 * The lifecycle these tests stand in for cannot be unit-tested — it needs a real Activity death, via
 * `adb shell settings put global always_finish_activities 1`. What *is* testable is the state machine
 * a recovery path depends on, which is all of the below.
 */
class SinglePendingOperationTest {

    private class Launch(val url: String)

    private class Listener {
        var terminals = 0
    }

    @Test
    fun `launch data transfers exactly once, so a recreation can tell it has nothing to start`() {
        val operation = SinglePendingOperation<Launch, Listener>()
        val entry = assertNotNull(operation.tryInstall(Launch("upi://pay"), Listener()))

        assertEquals("upi://pay", entry.takeData()?.url)
        // The second caller is a recreated Activity instance: same live operation, no data left.
        assertNull(entry.takeData())
        assertSame(entry, operation.live())
    }

    @Test
    fun `an operation stays installed after its data is taken`() {
        val operation = SinglePendingOperation<Launch, Listener>()
        val entry = assertNotNull(operation.tryInstall(Launch("upi://pay"), Listener()))
        entry.takeData()

        // The slot tracks the payment, not the launch — this is what lets a destroyed proxy find the
        // listener still waiting instead of an empty slot it can only guess about.
        assertSame(entry, operation.live())
    }

    @Test
    fun `a second install is refused while a payment is in flight`() {
        val operation = SinglePendingOperation<Launch, Listener>()
        val first = assertNotNull(operation.tryInstall(Launch("first"), Listener()))
        first.takeData()

        assertNull(operation.tryInstall(Launch("second"), Listener()))
        assertSame(first, operation.live())
    }

    @Test
    fun `the terminal is delivered exactly once`() {
        val operation = SinglePendingOperation<Launch, Listener>()
        val listener = Listener()
        val entry = assertNotNull(operation.tryInstall(Launch("upi://pay"), listener))

        assertTrue(entry.deliverTerminalOnce { it.terminals++ })
        // The loser of the race between a real vendor callback and a lifecycle recovery.
        assertFalse(entry.deliverTerminalOnce { it.terminals++ })
        assertEquals(1, listener.terminals)
    }

    @Test
    fun `settling releases the slot so the next payment can start`() {
        val operation = SinglePendingOperation<Launch, Listener>()
        val entry = assertNotNull(operation.tryInstall(Launch("first"), Listener()))
        entry.deliverTerminalOnce { it.terminals++ }

        assertNull(operation.live())
        assertNotNull(operation.tryInstall(Launch("second"), Listener()))
    }

    @Test
    fun `an operation with no listener is still consumed once`() {
        val operation = SinglePendingOperation<Launch, Listener>()
        val entry = assertNotNull(operation.tryInstall(Launch("upi://pay"), listener = null))

        // Nothing to call, but the terminal is spent and the slot freed — otherwise a listener-less
        // operation could be settled twice and would hold the slot forever.
        assertTrue(entry.deliverTerminalOnce { it.terminals++ })
        assertFalse(entry.deliverTerminalOnce { it.terminals++ })
        assertNull(operation.live())
    }

    @Test
    fun `clearIfOwned abandons only the current operation`() {
        val operation = SinglePendingOperation<Launch, Listener>()
        val first = assertNotNull(operation.tryInstall(Launch("first"), Listener()))

        assertTrue(operation.clearIfOwned(first))
        assertNull(operation.live())

        val second = assertNotNull(operation.tryInstall(Launch("second"), Listener()))
        // A stale launcher must never clear a later operation's slot.
        assertFalse(operation.clearIfOwned(first))
        assertSame(second, operation.live())
    }
}
