package io.dataloom.runtime.retry

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.RetryDelayHint
import io.dataloom.api.error.RetryDelayHintCarrier
import io.dataloom.api.error.RetryDelayHintSource
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RetryHintEvaluatorTest {

    private data class PlainError(
        override val code: ErrorCode = ErrorCode("DL-HINT-PLAIN"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized retry hint test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class HintError(
        override val retryDelayHint: RetryDelayHint,
        override val code: ErrorCode = ErrorCode("DL-HINT-CARRIER"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized retry hint carrier failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError, RetryDelayHintCarrier

    private val evaluator = RetryHintEvaluator(
        RetryHintConfiguration(maximumHintDelay = SchedulingDelay(5_000L)),
    )

    @Test
    fun `ordinary error has no bounded hint`() {
        assertNull(evaluator.boundedHint(PlainError()))
    }

    @Test
    fun `hint at or below maximum is preserved exactly`() {
        val hint = RetryDelayHint(
            delayMilliseconds = 5_000L,
            source = RetryDelayHintSource.SERVER,
        )

        assertEquals(hint, evaluator.boundedHint(HintError(hint)))
    }

    @Test
    fun `hint above maximum is clamped without changing source`() {
        val bounded = evaluator.boundedHint(
            HintError(
                RetryDelayHint(
                    delayMilliseconds = Long.MAX_VALUE,
                    source = RetryDelayHintSource.PROVIDER,
                ),
            ),
        )

        assertEquals(
            RetryDelayHint(
                delayMilliseconds = 5_000L,
                source = RetryDelayHintSource.PROVIDER,
            ),
            bounded,
        )
    }

    @Test
    fun `bounded hint becomes minimum retry delay and preserves metadata`() {
        val metadata = DataLoomMetadata.of(mapOf("hint" to "applied"))
        val adjusted = assertIs<RetryDecision.Retry>(
            evaluator.apply(
                decision = RetryDecision.Retry(
                    delay = SchedulingDelay(1_000L),
                    metadata = metadata,
                ),
                boundedHint = RetryDelayHint(
                    delayMilliseconds = 3_000L,
                    source = RetryDelayHintSource.SERVER,
                ),
            ),
        )

        assertEquals(SchedulingDelay(3_000L), adjusted.delay)
        assertEquals(metadata, adjusted.metadata)
    }

    @Test
    fun `policy delay longer than hint remains authoritative`() {
        val original = RetryDecision.Retry(delay = SchedulingDelay(7_000L))
        val adjusted = evaluator.apply(
            decision = original,
            boundedHint = RetryDelayHint(
                delayMilliseconds = 3_000L,
                source = RetryDelayHintSource.PROVIDER,
            ),
        )

        assertEquals(original, adjusted)
    }

    @Test
    fun `stop decision is never converted into retry`() {
        val original = RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
        val adjusted = evaluator.apply(
            decision = original,
            boundedHint = RetryDelayHint(
                delayMilliseconds = 3_000L,
                source = RetryDelayHintSource.SERVER,
            ),
        )

        assertEquals(original, adjusted)
    }
}
