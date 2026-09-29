package io.dataloom.api.error

import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RetryAfterParserTest {
    private class FixedClock(private val epochMilliseconds: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(epochMilliseconds)
    }

    private object FailingClock : DataLoomClock {
        override fun now(): DataLoomInstant = error("The delay-seconds form must not read the clock.")
    }

    private fun delayOf(value: String, clock: DataLoomClock? = FixedClock(HTTP_DATE_MINUS_30S)): Long? =
        RetryAfterParser.parse(listOf(value), clock)?.delayMilliseconds

    @Test
    fun `delay seconds table`() {
        val cases: List<Pair<String, Long?>> = listOf(
            "0" to 0L,
            "3" to 3_000L,
            "120" to 120_000L,
            "  7\t" to 7_000L,
            "00012" to 12_000L,
            "86400" to 86_400_000L,
            "86401" to RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS,
            "9223372036854775807" to RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS,
            "99999999999999999999999999999999" to RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS,
        )
        for ((input, expected) in cases) {
            assertEquals(expected, delayOf(input, clock = FailingClock), "input=[$input]")
            assertEquals(expected, delayOf(input, clock = null), "no clock, input=[$input]")
        }
    }

    @Test
    fun `malformed and negative values are rejected without throwing`() {
        val rejected: List<String> = listOf(
            "",
            " ",
            "-5",
            "-0",
            "+5",
            "5.5",
            "5s",
            "1e3",
            "0x10",
            "3, 5",
            "abc",
            "٣",
            "Sun, 06 Nov 1994 08:49:37 PST",
            "Sun, 06 Nov 1994 08:49:37",
            "Xyz, 06 Nov 1994 08:49:37 GMT",
            "Sun, 06 Foo 1994 08:49:37 GMT",
            "Sun, 31 Feb 1994 08:49:37 GMT",
            "Sun, 00 Nov 1994 08:49:37 GMT",
            "Sun, 06 Nov 1994 24:00:00 GMT",
            "Sun, 06 Nov 1994 08:60:00 GMT",
            "Sun, 06 Nov 1994 08:49 GMT",
            "Sun, 06 Nov 94x 08:49:37 GMT",
            "Sun, 06 Nov 199 08:49:37 GMT",
            "06 Nov 1994 08:49:37 GMT",
            "Sun Nov  6 08:49:37 94",
            "Thu, 29 Feb 1900 00:00:00 GMT",
            "Tomorrow",
            "\u0000",
        )
        for (input in rejected) {
            assertNull(delayOf(input), "input=[$input]")
        }
    }

    @Test
    fun `http date table in all three formats`() {
        val sameInstant: List<String> = listOf(
            "Sun, 06 Nov 1994 08:49:37 GMT",
            "sun, 06 nov 1994 08:49:37 gmt",
            "Sunday, 06-Nov-94 08:49:37 GMT",
            "Sun Nov  6 08:49:37 1994",
        )
        for (input in sameInstant) {
            assertEquals(30_000L, delayOf(input), "input=[$input]")
        }
    }

    @Test
    fun `http date past equal and future`() {
        val date = "Sun, 06 Nov 1994 08:49:37 GMT"
        assertEquals(
            0L,
            RetryAfterParser.parse(listOf(date), FixedClock(HTTP_DATE_EPOCH_MS + 5_000L))
                ?.delayMilliseconds,
        )
        assertEquals(
            0L,
            RetryAfterParser.parse(listOf(date), FixedClock(HTTP_DATE_EPOCH_MS))?.delayMilliseconds,
        )
        assertEquals(
            1L,
            RetryAfterParser.parse(listOf(date), FixedClock(HTTP_DATE_EPOCH_MS - 1L))
                ?.delayMilliseconds,
        )
        assertEquals(
            RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS,
            RetryAfterParser.parse(listOf(date), FixedClock(0L))?.delayMilliseconds,
        )
        assertEquals(
            RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS,
            delayOf("Fri, 31 Dec 9999 23:59:59 GMT"),
        )
    }

    @Test
    fun `http date needs a clock`() {
        assertNull(delayOf("Sun, 06 Nov 1994 08:49:37 GMT", clock = null))
    }

    @Test
    fun `http date calendar arithmetic`() {
        // 2038-01-19T03:14:07Z is epoch second 2147483647; 2024-02-29T12:00:00Z is 1709208000.
        assertEquals(
            1_500L,
            delayOf("Tue, 19 Jan 2038 03:14:07 GMT", clock = FixedClock(2_147_483_647_000L - 1_500L)),
        )
        assertEquals(
            2_000L,
            delayOf("Thu, 29 Feb 2024 12:00:00 GMT", clock = FixedClock(1_709_208_000_000L - 2_000L)),
        )
        assertEquals(
            0L,
            delayOf("Sat, 01 Jan 2000 00:00:00 GMT", clock = FixedClock(946_684_800_000L)),
        )
        assertEquals(
            1_000L,
            delayOf("Sat, 01 Jan 2000 00:00:00 GMT", clock = FixedClock(946_684_799_000L)),
        )
        // Two-digit years: 69 -> 2069, 70 -> 1970.
        assertNotNull(delayOf("Monday, 01-Jan-69 00:00:00 GMT"))
        assertEquals(
            0L,
            delayOf("Thursday, 01-Jan-70 00:00:00 GMT", clock = FixedClock(0L)),
        )
        assertEquals(
            1_000L,
            delayOf("Thursday, 01-Jan-70 00:00:01 GMT", clock = FixedClock(0L)),
        )
    }

    @Test
    fun `only the first of several values is used`() {
        assertEquals(3_000L, RetryAfterParser.parse(listOf("3", "10"), null)?.delayMilliseconds)
        assertNull(RetryAfterParser.parse(listOf("bogus", "3"), null))
        assertNull(RetryAfterParser.parse(emptyList(), FixedClock(0L)))
    }

    @Test
    fun `hint source is SERVER`() {
        assertEquals(
            RetryDelayHintSource.SERVER,
            RetryAfterParser.parse(listOf("1"), null)?.source,
        )
    }

    private companion object {
        const val HTTP_DATE_EPOCH_MS: Long = 784_111_777_000L
        const val HTTP_DATE_MINUS_30S: Long = HTTP_DATE_EPOCH_MS - 30_000L
    }
}
