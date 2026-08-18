package com.getlokalapp.paymentsdk.upiintent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `upiParam` is the highest-risk pure function in the SDK: its input is a **string handed back
 * by a third-party UPI app** through an Android intent result. Apps vary in casing, omit
 * fields, and return values containing the separator characters, so the parser has to be
 * total — every malformed shape must yield null rather than a wrong value or an exception.
 *
 * The status it feeds is advisory only ([ClientStatus] is explicit that a FAILURE hint can
 * still be a debited payment), so the tests assert the parse, never a payment conclusion.
 */
class UpiIntentResultMapperTest {

    // --- upiParam ------------------------------------------------------------------

    @Test
    fun `reads a param from a full upi url`() {
        val url = "upi://pay?pa=merchant@bank&tr=TXN123&Status=SUCCESS"

        assertEquals("TXN123", url.upiParam("tr"))
        assertEquals("merchant@bank", url.upiParam("pa"))
    }

    @Test
    fun `reads a param from a bare query string`() {
        // The real Android intent response has no leading '?' — this is the common case.
        val response = "txnId=ABC&Status=SUCCESS&txnRef=TXN123"

        assertEquals("ABC", response.upiParam("txnId"))
        assertEquals("SUCCESS", response.upiParam("Status"))
    }

    @Test
    fun `matches the key case-insensitively`() {
        // Apps disagree on casing: Status vs status vs STATUS.
        val response = "Status=SUCCESS"

        assertEquals("SUCCESS", response.upiParam("status"))
        assertEquals("SUCCESS", response.upiParam("STATUS"))
        assertEquals("SUCCESS", response.upiParam("Status"))
    }

    @Test
    fun `keeps a value that itself contains equals signs`() {
        // Base64 signatures routinely contain '=' padding, so splitting on every '=' would
        // silently truncate them.
        val response = "Status=SUCCESS&sign=YWJjZA==&tr=TXN1"

        assertEquals("YWJjZA==", response.upiParam("sign"))
    }

    @Test
    fun `returns an empty string for a present-but-empty value`() {
        // Distinct from absent: the app answered, with nothing.
        assertEquals("", "tr=&Status=SUCCESS".upiParam("tr"))
    }

    @Test
    fun `returns null for an absent key`() {
        assertNull("Status=SUCCESS".upiParam("tr"))
    }

    @Test
    fun `returns null for empty and malformed input`() {
        assertNull("".upiParam("tr"))
        assertNull("   ".upiParam("tr"))
        // A bare token with no '=' is not a key/value pair.
        assertNull("novalue".upiParam("novalue"))
        // A pair beginning with '=' has an empty key; the eq > 0 guard rejects it.
        assertNull("=orphan".upiParam(""))
    }

    @Test
    fun `ignores a key that only appears outside the query`() {
        // "pay" appears in the path, not as a parameter.
        assertNull("upi://pay?tr=TXN1".upiParam("upi://pay"))
    }

    @Test
    fun `takes the first occurrence when a key repeats`() {
        assertEquals("first", "tr=first&tr=second".upiParam("tr"))
    }

    @Test
    fun `finds a param that is not the first pair`() {
        assertEquals("TXN1", "pa=m@bank&pn=Merchant&am=10.00&tr=TXN1".upiParam("tr"))
    }

    // --- parseClientStatus ---------------------------------------------------------

    @Test
    fun `maps the terminal statuses an app can report`() {
        assertEquals(ClientStatus.SUCCESS, parseClientStatus("Status=SUCCESS"))
        assertEquals(ClientStatus.FAILURE, parseClientStatus("Status=FAILURE"))
        // Some apps abbreviate.
        assertEquals(ClientStatus.FAILURE, parseClientStatus("Status=FAIL"))
    }

    @Test
    fun `maps status case-insensitively`() {
        assertEquals(ClientStatus.SUCCESS, parseClientStatus("status=success"))
        assertEquals(ClientStatus.FAILURE, parseClientStatus("STATUS=Failure"))
    }

    @Test
    fun `treats a missing or unparseable response as unknown`() {
        // The documented common case: many apps return nothing at all. iOS always lands here,
        // since it has no intent-result callback.
        assertEquals(ClientStatus.UNKNOWN, parseClientStatus(null))
        assertEquals(ClientStatus.UNKNOWN, parseClientStatus(""))
        assertEquals(ClientStatus.UNKNOWN, parseClientStatus("garbage"))
        assertEquals(ClientStatus.UNKNOWN, parseClientStatus("Status="))
    }

    @Test
    fun `treats non-terminal statuses as unknown rather than guessing`() {
        // SUBMITTED/PENDING mean the app has not decided; mapping either to SUCCESS or FAILURE
        // would invent a conclusion the backend has not reached.
        assertEquals(ClientStatus.UNKNOWN, parseClientStatus("Status=SUBMITTED"))
        assertEquals(ClientStatus.UNKNOWN, parseClientStatus("Status=PENDING"))
        assertEquals(ClientStatus.UNKNOWN, parseClientStatus("Status=IN_PROGRESS"))
    }

    // --- withUpiScheme -------------------------------------------------------------

    @Test
    fun `rewrites the scheme keeping host and query intact`() {
        assertEquals(
            "phonepe://mandate?pa=m@bank&tr=TXN1",
            "upi://mandate?pa=m@bank&tr=TXN1".withUpiScheme("phonepe"),
        )
    }

    @Test
    fun `rewrites only the first scheme separator`() {
        // A redirect URL inside the query must not be rewritten too.
        assertEquals(
            "tez://pay?url=https://example.com",
            "upi://pay?url=https://example.com".withUpiScheme("tez"),
        )
    }

    @Test
    fun `returns the string unchanged when it has no scheme separator`() {
        assertEquals("not-a-url", "not-a-url".withUpiScheme("phonepe"))
        assertEquals("", "".withUpiScheme("phonepe"))
    }

    // --- isGenericUpiScheme --------------------------------------------------------

    @Test
    fun `recognizes the generic upi scheme`() {
        assertTrue("upi://pay?tr=TXN1".isGenericUpiScheme())
        assertTrue("upi://mandate?tr=TXN1".isGenericUpiScheme())
        // Case-insensitive, since the backend builds this URL.
        assertTrue("UPI://pay".isGenericUpiScheme())
    }

    @Test
    fun `an app-specific scheme is not generic`() {
        // These name their target already, so the SDK must open them directly with no chooser.
        listOf("phonepe://pay", "tez://upi/pay", "paytmmp://pay", "https://example.com").forEach { url ->
            assertFalse(url.isGenericUpiScheme(), "$url was treated as the generic upi scheme")
        }
    }

    @Test
    fun `a string with no scheme is not generic`() {
        assertFalse("pay".isGenericUpiScheme())
        assertFalse("".isGenericUpiScheme())
        // "upi" alone is not a scheme without the separator.
        assertFalse("upi".isGenericUpiScheme())
    }
}
