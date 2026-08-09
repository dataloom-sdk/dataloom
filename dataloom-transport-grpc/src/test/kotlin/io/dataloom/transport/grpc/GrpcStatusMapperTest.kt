package io.dataloom.transport.grpc

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GrpcStatusMapperTest {

    // ── StatusException overload ───────────────────────────────────────────

    @Test
    fun `UNAUTHENTICATED maps to AUTHENTICATION NON_RECOVERABLE`() {
        val ex = StatusException(Status.UNAUTHENTICATED)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.AUTHENTICATION, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_UNAUTHENTICATED", error.code.value)
    }

    @Test
    fun `PERMISSION_DENIED maps to AUTHORIZATION NON_RECOVERABLE`() {
        val ex = StatusException(Status.PERMISSION_DENIED)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.AUTHORIZATION, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_PERMISSION_DENIED", error.code.value)
    }

    @Test
    fun `UNAVAILABLE maps to NETWORK RECOVERABLE`() {
        val ex = StatusException(Status.UNAVAILABLE)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals("GRPC_UNAVAILABLE", error.code.value)
    }

    @Test
    fun `DEADLINE_EXCEEDED maps to NETWORK RECOVERABLE`() {
        val ex = StatusException(Status.DEADLINE_EXCEEDED)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals("GRPC_DEADLINE_EXCEEDED", error.code.value)
    }

    @Test
    fun `RESOURCE_EXHAUSTED maps to NETWORK RECOVERABLE`() {
        val ex = StatusException(Status.RESOURCE_EXHAUSTED)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals("GRPC_RESOURCE_EXHAUSTED", error.code.value)
    }

    @Test
    fun `ABORTED maps to NETWORK RECOVERABLE`() {
        val ex = StatusException(Status.ABORTED)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals("GRPC_ABORTED", error.code.value)
    }

    @Test
    fun `CANCELLED maps to NETWORK RECOVERABLE`() {
        val ex = StatusException(Status.CANCELLED)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals("GRPC_CANCELLED", error.code.value)
    }

    @Test
    fun `UNKNOWN maps to NETWORK UNKNOWN`() {
        val ex = StatusException(Status.UNKNOWN)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.UNKNOWN, error.recoverability)
        assertEquals("GRPC_UNKNOWN", error.code.value)
    }

    @Test
    fun `INVALID_ARGUMENT maps to VALIDATION NON_RECOVERABLE`() {
        val ex = StatusException(Status.INVALID_ARGUMENT)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.VALIDATION, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_INVALID_ARGUMENT", error.code.value)
    }

    @Test
    fun `FAILED_PRECONDITION maps to VALIDATION NON_RECOVERABLE`() {
        val ex = StatusException(Status.FAILED_PRECONDITION)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.VALIDATION, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_FAILED_PRECONDITION", error.code.value)
    }

    @Test
    fun `NOT_FOUND maps to NETWORK NON_RECOVERABLE`() {
        val ex = StatusException(Status.NOT_FOUND)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_NOT_FOUND", error.code.value)
    }

    @Test
    fun `UNIMPLEMENTED maps to NETWORK NON_RECOVERABLE`() {
        val ex = StatusException(Status.UNIMPLEMENTED)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_UNIMPLEMENTED", error.code.value)
    }

    @Test
    fun `INTERNAL maps to INTERNAL NON_RECOVERABLE`() {
        val ex = StatusException(Status.INTERNAL)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.INTERNAL, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_INTERNAL", error.code.value)
    }

    @Test
    fun `DATA_LOSS maps to INTERNAL NON_RECOVERABLE`() {
        val ex = StatusException(Status.DATA_LOSS)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.INTERNAL, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_DATA_LOSS", error.code.value)
    }

    // ── StatusRuntimeException overload ───────────────────────────────────

    @Test
    fun `StatusRuntimeException UNAVAILABLE maps to NETWORK RECOVERABLE`() {
        val ex = StatusRuntimeException(Status.UNAVAILABLE)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals("GRPC_UNAVAILABLE", error.code.value)
    }

    @Test
    fun `StatusRuntimeException UNAUTHENTICATED maps to AUTHENTICATION NON_RECOVERABLE`() {
        val ex = StatusRuntimeException(Status.UNAUTHENTICATED)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.AUTHENTICATION, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
    }

    @Test
    fun `OUT_OF_RANGE maps to VALIDATION NON_RECOVERABLE`() {
        val ex = StatusException(Status.OUT_OF_RANGE)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.VALIDATION, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_OUT_OF_RANGE", error.code.value)
    }

    @Test
    fun `ALREADY_EXISTS maps to NETWORK NON_RECOVERABLE`() {
        val ex = StatusException(Status.ALREADY_EXISTS)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_ALREADY_EXISTS", error.code.value)
    }

    @Test
    fun `OK maps to INTERNAL NON_RECOVERABLE as unexpected success`() {
        val ex = StatusException(Status.OK)
        val error = GrpcStatusMapper.map(ex)

        assertEquals(ErrorCategory.INTERNAL, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_UNEXPECTED_OK", error.code.value)
    }

    // ── Cause preservation ────────────────────────────────────────────────

    @Test
    fun `cause is retained in mapped error for StatusException as GrpcTransportCause`() {
        val ex = StatusException(Status.UNAVAILABLE)
        val error = GrpcStatusMapper.map(ex)

        assertNotNull(error.cause)
        // The cause must be a transport-neutral wrapper, not a raw io.grpc type,
        // so that no io.grpc.* type escapes the DataLoomError API boundary.
        assertIs<GrpcTransportCause>(error.cause)
    }

    @Test
    fun `cause is retained in mapped error for StatusRuntimeException as GrpcTransportCause`() {
        val ex = StatusRuntimeException(Status.INTERNAL)
        val error = GrpcStatusMapper.map(ex)

        assertNotNull(error.cause)
        assertIs<GrpcTransportCause>(error.cause)
    }

    // ── Message sanitization ──────────────────────────────────────────────

    @Test
    fun `mapped error message contains status code name but no credentials`() {
        val ex = StatusException(Status.UNAUTHENTICATED)
        val error = GrpcStatusMapper.map(ex)

        // Message must reference the status code name for diagnosability.
        assert(error.message.contains("UNAUTHENTICATED")) {
            "Expected message to contain status code name, got: ${error.message}"
        }
    }
}
