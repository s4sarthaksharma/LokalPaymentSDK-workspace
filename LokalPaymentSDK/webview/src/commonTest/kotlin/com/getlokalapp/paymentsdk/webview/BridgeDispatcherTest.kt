package com.getlokalapp.paymentsdk.webview

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.testkit.RecordingLogger
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BridgeDispatcher] is the platform-agnostic core of the JS bridge: it parses an envelope
 * posted by the page, routes it to the matching [JsBridgeHandler], and builds the reply
 * script. Everything here is common code with the per-platform `evaluate` injected, so it
 * needs no WebView and runs on every target.
 *
 * The theme is **isolation**: a page is untrusted input and host handlers are third-party
 * extension points, so neither may crash the WebView transport. Where the SDK swallows a
 * failure it must still report it, which is what the [RecordingLogger] assertions pin.
 */
class BridgeDispatcherTest {

    private lateinit var logger: RecordingLogger
    private val evaluated = mutableListOf<String>()

    /** Records what the page sent, and optionally replies. */
    private class CapturingHandler(
        override val name: String,
        private val replyWith: String? = null,
    ) : JsBridgeHandler {
        val payloads = mutableListOf<String>()
        var replyCount = 0

        override fun onMessage(payload: String, reply: (String) -> Unit) {
            payloads += payload
            replyWith?.let {
                replyCount++
                reply(it)
            }
        }
    }

    private fun dispatcher(vararg handlers: JsBridgeHandler): BridgeDispatcher =
        BridgeDispatcher(
            config = WebViewConfig(
                handlers = handlers.toList(),
                bridgeHosts = setOf(trustedWebHostOf("https", "checkout.example.com")!!),
            ),
            evaluate = { evaluated += it },
        )

    /**
     * Builds the envelope the JS shim posts. [id] is JSON-encoded rather than interpolated:
     * a hostile id arrives from the page already escaped, and interpolating it raw would
     * produce an unparseable envelope, silently testing the malformed-input path instead.
     */
    private fun envelope(name: String, payload: String = "null", id: String? = "m0"): String =
        if (id == null) {
            """{"name":"$name","payload":$payload}"""
        } else {
            """{"name":"$name","payload":$payload,"id":${JsonPrimitive(id)}}"""
        }

    @BeforeTest
    fun installLogger() {
        logger = RecordingLogger()
        LokalPaymentSdk.setLogger(logger)
    }

    @AfterTest
    fun removeLogger() {
        LokalPaymentSdk.setLogger(null)
    }

    // --- routing ------------------------------------------------------------------

    @Test
    fun `routes an envelope to the handler matching its name`() {
        val wanted = CapturingHandler("PAYMENT_SUCCESS")
        val other = CapturingHandler("PAYMENT_FAILED")

        dispatcher(wanted, other).dispatch(envelope("PAYMENT_SUCCESS", """{"id":"pay_1"}"""))

        assertEquals(listOf("""{"id":"pay_1"}"""), wanted.payloads)
        assertEquals(emptyList(), other.payloads)
    }

    @Test
    fun `ignores an envelope whose name has no handler`() {
        val handler = CapturingHandler("PAYMENT_SUCCESS")

        dispatcher(handler).dispatch(envelope("SOMETHING_ELSE"))

        assertEquals(emptyList(), handler.payloads)
        // An unknown message is normal page chatter, not an SDK anomaly.
        logger.assertNoNonFatals()
    }

    @Test
    fun `last handler wins when two share a name`() {
        // WebViewConfig documents "duplicate names: last one wins" — this pins the
        // associateBy behavior that implements it.
        val first = CapturingHandler("DUPLICATE")
        val second = CapturingHandler("DUPLICATE")

        dispatcher(first, second).dispatch(envelope("DUPLICATE"))

        assertEquals(emptyList(), first.payloads)
        assertEquals(listOf("null"), second.payloads)
    }

    // --- malformed input from the page --------------------------------------------

    @Test
    fun `ignores malformed envelopes without crashing`() {
        val handler = CapturingHandler("PAYMENT_SUCCESS")
        val dispatcher = dispatcher(handler)

        listOf(
            "",
            "not json",
            "{",
            "[]",
            "null",
            """{"payload":"orphan"}""",              // no name
            """{"name":123}""",                       // name is not a string
            """{"name":"PAYMENT_SUCCESS","payload":}""",
        ).forEach { raw -> dispatcher.dispatch(raw) }

        assertEquals(emptyList(), handler.payloads, "A malformed envelope reached a handler")
        assertEquals(emptyList(), evaluated, "A malformed envelope produced a reply")
    }

    @Test
    fun `normalizes an absent payload to the string null`() {
        val handler = CapturingHandler("PAYMENT_SUCCESS")

        dispatcher(handler).dispatch("""{"name":"PAYMENT_SUCCESS","id":"m0"}""")

        // WebViewConfig promises the payload is "normalized identically on Android and iOS",
        // so an absent payload must be a stable string rather than a platform-shaped value.
        assertEquals(listOf("null"), handler.payloads)
    }

    @Test
    fun `passes through non-object payloads verbatim`() {
        val handler = CapturingHandler("EVENT")

        val dispatcher = dispatcher(handler)
        dispatcher.dispatch(envelope("EVENT", """"a string""""))
        dispatcher.dispatch(envelope("EVENT", "42"))
        dispatcher.dispatch(envelope("EVENT", "[1,2]"))

        assertEquals(listOf(""""a string"""", "42", "[1,2]"), handler.payloads)
    }

    // --- replies ------------------------------------------------------------------

    @Test
    fun `resolves the awaiting promise when the handler replies`() {
        val handler = CapturingHandler("ASK", replyWith = "ok")

        dispatcher(handler).dispatch(envelope("ASK", id = "m7"))

        assertEquals(listOf("""window.$REPLY_FN("m7", "ok");"""), evaluated)
    }

    @Test
    fun `escapes quotes and newlines in a reply instead of interpolating them`() {
        // The reply is evaluated as JavaScript in the page, so a raw string would be an
        // injection point. Both id and result go through JsonPrimitive for this reason.
        val hostile = """a" ; window.stolen = document.cookie; //"""
        val handler = CapturingHandler("ASK", replyWith = hostile)

        dispatcher(handler).dispatch(envelope("ASK", id = "m0"))

        val script = evaluated.single()
        assertTrue(
            """\"""" in script,
            "The quote was not escaped, so the reply can break out of its string: $script",
        )
        assertTrue(
            "window.stolen = document.cookie" !in script.substringBefore("""\""""),
            "Injected code appeared outside the quoted string: $script",
        )
    }

    @Test
    fun `escapes a newline in a reply`() {
        val handler = CapturingHandler("ASK", replyWith = "line1\nline2")

        dispatcher(handler).dispatch(envelope("ASK", id = "m0"))

        val script = evaluated.single()
        assertTrue("""\n""" in script, "Newline was not escaped: $script")
        assertEquals(1, script.lines().size, "The reply script spans multiple lines: $script")
    }

    @Test
    fun `escapes a hostile message id`() {
        val handler = CapturingHandler("ASK", replyWith = "ok")

        dispatcher(handler).dispatch(envelope("ASK", id = """m0" ; alert(1); //"""))

        val script = evaluated.single()
        assertTrue("""\"""" in script, "The id was not escaped: $script")
    }

    @Test
    fun `does not reply when the page did not await`() {
        // No id means the page called postMessage without awaiting the promise.
        val handler = CapturingHandler("FIRE_AND_FORGET", replyWith = "ignored")

        dispatcher(handler).dispatch(envelope("FIRE_AND_FORGET", id = null))

        assertEquals(listOf("null"), handler.payloads)
        assertEquals(emptyList(), evaluated, "A reply was evaluated for a message with no id")
    }

    @Test
    fun `a handler replying twice evaluates twice`() {
        // JsBridgeHandler documents "call it at most once"; this records what actually
        // happens if a handler breaks that, so the behavior is known rather than assumed.
        val handler = object : JsBridgeHandler {
            override val name = "ASK"
            override fun onMessage(payload: String, reply: (String) -> Unit) {
                reply("first")
                reply("second")
            }
        }

        dispatcher(handler).dispatch(envelope("ASK", id = "m0"))

        assertEquals(2, evaluated.size)
        // The JS shim deletes the pending callback on the first reply, so the second is a
        // no-op in the page. Nothing is reported: this is a page-side contract, not an SDK
        // anomaly.
        logger.assertNoNonFatals()
    }

    // --- failure isolation --------------------------------------------------------

    @Test
    fun `isolates a throwing handler and reports it`() {
        val throwing = object : JsBridgeHandler {
            override val name = "BOOM"
            override fun onMessage(payload: String, reply: (String) -> Unit) =
                throw IllegalStateException("host handler blew up")
        }

        // Must not propagate: this call arrives on a platform WebView transport callback.
        dispatcher(throwing).dispatch(envelope("BOOM"))

        val reported = logger.nonFatals.single()
        assertEquals("bridge handler", reported.extras["callback"])
        assertEquals("host handler blew up", reported.throwable?.message)
    }

    @Test
    fun `isolates a throwing evaluate and reports it as a reply failure`() {
        val handler = CapturingHandler("ASK", replyWith = "ok")
        val dispatcher = BridgeDispatcher(
            config = WebViewConfig(
                handlers = listOf(handler),
                bridgeHosts = setOf(trustedWebHostOf("https", "checkout.example.com")!!),
            ),
            evaluate = { throw IllegalStateException("webview is gone") },
        )

        dispatcher.dispatch(envelope("ASK", id = "m0"))

        val reported = logger.nonFatals.single()
        assertEquals("bridge reply", reported.extras["callback"])
        assertEquals(1, handler.replyCount, "The handler should still have run to completion")
    }

    @Test
    fun `a throwing handler does not stop later messages`() {
        val throwing = object : JsBridgeHandler {
            override val name = "BOOM"
            override fun onMessage(payload: String, reply: (String) -> Unit) =
                throw IllegalStateException("boom")
        }
        val healthy = CapturingHandler("FINE")
        val dispatcher = dispatcher(throwing, healthy)

        dispatcher.dispatch(envelope("BOOM"))
        dispatcher.dispatch(envelope("FINE"))

        assertEquals(listOf("null"), healthy.payloads)
    }
}
