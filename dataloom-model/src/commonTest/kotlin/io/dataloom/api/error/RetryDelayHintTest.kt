package io.dataloom.api.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryDelayHintTest {

    @Test
    fun `zero and maximum delays are valid`() {
        assertEquals(
            0L,
            RetryDelayHint(
                delayMilliseconds = 0L,
                source = RetryDelayHintSource.SERVER,
            ).delayMilliseconds,
        )
        assertEquals(
            Long.MAX_VALUE,
            RetryDelayHint(
                delayMilliseconds = Long.MAX_VALUE,
                source = RetryDelayHintSource.PROVIDER,
            ).delayMilliseconds,
        )
    }

    @Test
    fun `negative delay is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RetryDelayHint(
                delayMilliseconds = -1L,
                source = RetryDelayHintSource.SERVER,
            )
        }
    }

    @Test
    fun `source names are stable and distinct`() {
        assertEquals(
            setOf("SERVER", "PROVIDER"),
            RetryDelayHintSource.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `carrier exposes exact immutable hint`() {
        val expected = RetryDelayHint(
            delayMilliseconds = 5_000L,
            source = RetryDelayHintSource.SERVER,
        )
        val carrier = object : RetryDelayHintCarrier {
            override val retryDelayHint: RetryDelayHint = expected
        }

        assertEquals(expected, carrier.retryDelayHint)
    }
}
