package io.dataloom.transport.ktor

import io.dataloom.api.error.RetryDelayHintCarrier
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Table-driven coverage of the Ktor transport's `Retry-After` handling, on top
 * of the existing 429-numeric-seconds case in [KtorTransportProviderTest].
 *
 * This exercises the full [KtorTransportProvider] (mock HTTP response through
 * to the produced [io.dataloom.api.error.DataLoomError]), not just
 * [io.dataloom.api.error.RetryAfterParser] in isolation, so it also proves the
 * hint reaches [RetryDelayHintCarrier] on the real provider path — the seam
 * [io.dataloom.runtime.retry.RetryHintEvaluator] and
 * [io.dataloom.runtime.retry.SynchronizationRetryEvaluator] consume, per
 * `RetryHintRuntimeIntegrationTest`.
 */
class KtorRetryAfterTest {
    private class FixedClock(private val epochMilliseconds: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(epochMilliseconds)
    }

    @Test
    fun `delta seconds`() = runTest {
        val error = retryAfterFailure(429, listOf("Retry-After" to "3"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `http date in the future`() = runTest {
        val error = retryAfterFailure(
            503,
            listOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"),
            clock = FixedClock(784_111_777_000L - 30_000L),
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(30_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `http date in the past clamps to zero`() = runTest {
        val error = retryAfterFailure(
            429,
            listOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"),
            clock = FixedClock(784_111_777_000L + 60_000L),
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(0L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `http date without a configured clock produces no hint`() = runTest {
        val error = retryAfterFailure(
            429,
            listOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"),
            clock = null,
        )
        assertNull(error as? RetryDelayHintCarrier)
        val classified = assertIs<ClassifiedStrategyRemoteError>(error)
        assertEquals(StrategyRemoteOutcome.RATE_LIMITED, classified.remoteOutcome)
    }

    @Test
    fun `malformed value produces no hint but still classifies the status`() = runTest {
        val error = retryAfterFailure(429, listOf("Retry-After" to "not-a-delay"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
        val classified = assertIs<ClassifiedStrategyRemoteError>(error)
        assertEquals(StrategyRemoteOutcome.RATE_LIMITED, classified.remoteOutcome)
    }

    @Test
    fun `negative value produces no hint`() = runTest {
        val error = retryAfterFailure(429, listOf("Retry-After" to "-5"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
    }

    @Test
    fun `huge value clamps to the maximum delay`() = runTest {
        val error = retryAfterFailure(
            429,
            listOf("Retry-After" to "99999999999999999999999"),
            clock = null,
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(io.dataloom.api.error.RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `missing header produces no hint`() = runTest {
        val error = retryAfterFailure(429, emptyList(), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
        val classified = assertIs<ClassifiedStrategyRemoteError>(error)
        assertEquals(StrategyRemoteOutcome.RATE_LIMITED, classified.remoteOutcome)
    }

    @Test
    fun `multiple headers use the first value`() = runTest {
        val error = retryAfterFailure(
            429,
            listOf("Retry-After" to "3", "Retry-After" to "300"),
            clock = null,
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `header name is case insensitive`() = runTest {
        val error = retryAfterFailure(429, listOf("retry-after" to "3"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `503 without Retry-After produces no hint`() = runTest {
        val error = retryAfterFailure(503, emptyList(), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
        val classified = assertIs<ClassifiedStrategyRemoteError>(error)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, classified.remoteOutcome)
    }

    @Test
    fun `502 never produces a hint even with the header present`() = runTest {
        val error = retryAfterFailure(502, listOf("Retry-After" to "3"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
        val classified = assertIs<ClassifiedStrategyRemoteError>(error)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, classified.remoteOutcome)
    }
}
