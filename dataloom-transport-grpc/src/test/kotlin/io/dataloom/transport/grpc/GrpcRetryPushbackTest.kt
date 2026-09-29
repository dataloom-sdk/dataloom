package io.dataloom.transport.grpc

import io.dataloom.api.error.RetryAfterParser
import io.dataloom.api.error.RetryDelayHintCarrier
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Table-driven coverage of [GrpcStatusMapper]'s `grpc-retry-pushback-ms`
 * status-trailer handling (the gRPC equivalent of the HTTP transports'
 * `Retry-After`; see [io.dataloom.api.error.RetryAfterParser]).
 */
class GrpcRetryPushbackTest {
    private val pushbackKey: Metadata.Key<String> =
        Metadata.Key.of("grpc-retry-pushback-ms", Metadata.ASCII_STRING_MARSHALLER)

    private fun trailersOf(vararg values: String): Metadata {
        val metadata = Metadata()
        values.forEach { value -> metadata.put(pushbackKey, value) }
        return metadata
    }

    @Test
    fun `pushback milliseconds table`() {
        val cases: List<Pair<String, Long?>> = listOf(
            "0" to 0L,
            "3" to 3L,
            "1500" to 1_500L,
            "86400000" to 86_400_000L,
            "86400001" to RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS,
            "9223372036854775807" to RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS,
            "99999999999999999999999999999999" to RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS,
        )
        for ((input, expected) in cases) {
            val error = GrpcStatusMapper.map(
                StatusException(Status.RESOURCE_EXHAUSTED, trailersOf(input)),
            )
            assertEquals(expected, (error as? RetryDelayHintCarrier)?.retryDelayHint?.delayMilliseconds, "input=[$input]")
        }
    }

    @Test
    fun `malformed negative and huge-but-non-numeric values produce no hint`() {
        val rejected = listOf("", " ", "-5", "-0", "+5", "5.5", "5ms", "abc", "1,500")
        for (input in rejected) {
            val error = GrpcStatusMapper.map(StatusException(Status.RESOURCE_EXHAUSTED, trailersOf(input)))
            assertNull(error as? RetryDelayHintCarrier, "input=[$input]")
        }
    }

    @Test
    fun `missing trailer produces no hint`() {
        val error = GrpcStatusMapper.map(StatusException(Status.RESOURCE_EXHAUSTED, Metadata()))
        assertNull(error as? RetryDelayHintCarrier)
    }

    @Test
    fun `null trailers produce no hint`() {
        val error = GrpcStatusMapper.map(StatusException(Status.RESOURCE_EXHAUSTED))
        assertNull(error as? RetryDelayHintCarrier)
    }

    @Test
    fun `multiple values use the first one gRPC metadata returns`() {
        val error = GrpcStatusMapper.map(
            StatusException(Status.RESOURCE_EXHAUSTED, trailersOf("3", "300")),
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `UNAVAILABLE also honors the pushback trailer`() {
        val error = GrpcStatusMapper.map(
            StatusRuntimeException(Status.UNAVAILABLE, trailersOf("42")),
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(42L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `other RECOVERABLE codes never produce a hint even with the trailer present`() {
        val recoverableButNotThrottled = listOf(Status.ABORTED, Status.CANCELLED, Status.DEADLINE_EXCEEDED)
        for (status in recoverableButNotThrottled) {
            val error = GrpcStatusMapper.map(StatusException(status, trailersOf("100")))
            assertNull(error as? RetryDelayHintCarrier, "status=$status")
        }
    }

    @Test
    fun `NON_RECOVERABLE codes never produce a hint even with the trailer present`() {
        val error = GrpcStatusMapper.map(StatusException(Status.PERMISSION_DENIED, trailersOf("100")))
        assertNull(error as? RetryDelayHintCarrier)
    }

    @Test
    fun `StatusRuntimeException path is equivalent to StatusException`() {
        val error = GrpcStatusMapper.map(
            StatusRuntimeException(Status.RESOURCE_EXHAUSTED, trailersOf("7")),
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(7L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `hint reaches the mapped error alongside its existing RECOVERABLE classification`() {
        // End-to-end seam for gRPC: GrpcTransportProviderTest exercises the full
        // provider → GrpcStatusMapper path without a trailer; this proves the
        // same path also carries a hint through when the trailer is present,
        // without changing the RECOVERABLE classification GrpcStatusMapperTest
        // already pins for RESOURCE_EXHAUSTED.
        val error = GrpcStatusMapper.map(StatusException(Status.RESOURCE_EXHAUSTED, trailersOf("250")))

        assertEquals(io.dataloom.api.error.Recoverability.RECOVERABLE, error.recoverability)
        assertEquals("GRPC_RESOURCE_EXHAUSTED", error.code.value)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(250L, hint.retryDelayHint.delayMilliseconds)
    }
}
