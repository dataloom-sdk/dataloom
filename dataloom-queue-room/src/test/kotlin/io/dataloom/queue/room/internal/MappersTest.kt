package io.dataloom.queue.room.internal

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConfigurationVersion
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.LocaleTag
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RequestId
import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.UserId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Pure mapper tests; Android JSON metadata round trips are covered by instrumentation tests. */
class MappersTest {

    private fun request(context: ExecutionContext = ExecutionContext(
        executionId = ExecutionId("exec-1"),
        correlationId = CorrelationId("corr-1"),
    )): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        priority = WorkflowPriority.NORMAL,
        context = context,
    )

    private fun pendingEntry(id: String = "entry-1"): QueueEntry = QueueEntry(
        id = QueueEntryId(id),
        synchronizationRequest = request(),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
    )

    @Test
    fun `toEntity preserves identifiers enums and timestamps`() {
        val entity = pendingEntry("entry-a").toEntity()
        assertEquals("entry-a", entity.entryId)
        assertEquals("workflow-1", entity.workflowId)
        assertEquals("session-1", entity.sessionId)
        assertEquals("PUSH", entity.direction)
        assertEquals("DELTA", entity.mode)
        assertEquals("NORMAL", entity.priority)
        assertEquals("PENDING", entity.state)
        assertEquals(1_000L, entity.enqueuedAtMs)
        assertEquals(1_000L, entity.availableAtMs)
        assertNull(entity.leaseId)
    }

    @Test
    fun `round trip pending entry preserves complete domain value`() {
        val original = pendingEntry()
        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `round trip preserves optional execution context identifiers`() {
        val context = ExecutionContext(
            executionId = ExecutionId("exec-full"),
            correlationId = CorrelationId("corr-full"),
            traceId = TraceId("trace-1"),
            requestId = RequestId("request-1"),
            tenantId = TenantId("tenant-1"),
            userId = UserId("user-1"),
            localeTag = LocaleTag("en-US"),
            runtimeVersion = RuntimeVersion("1.2.3"),
            configurationVersion = ConfigurationVersion("v2"),
        )
        val original = QueueEntry(
            id = QueueEntryId("optional-context"),
            synchronizationRequest = request(context),
            state = QueueEntryState.PENDING,
            enqueuedAt = DataLoomInstant(1_000L),
            availableAt = DataLoomInstant(1_000L),
        )
        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `round trip retry waiting entry preserves attempt`() {
        val original = QueueEntry(
            id = QueueEntryId("retry-entry"),
            synchronizationRequest = request(),
            state = QueueEntryState.RETRY_WAITING,
            enqueuedAt = DataLoomInstant(1_000L),
            availableAt = DataLoomInstant(2_000L),
            retryAttempt = RetryAttempt(3),
        )
        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `round trip leased entry preserves lease identity`() {
        val lease = QueueLease(
            id = QueueLeaseId("lease-1"),
            consumerId = QueueConsumerId("consumer-1"),
            acquiredAt = DataLoomInstant(1_500L),
            expiresAt = DataLoomInstant(2_500L),
        )
        val original = QueueEntry(
            id = QueueEntryId("leased-entry"),
            synchronizationRequest = request(),
            state = QueueEntryState.LEASED,
            enqueuedAt = DataLoomInstant(1_000L),
            availableAt = DataLoomInstant(1_000L),
            lease = lease,
        )
        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `round trip preserves every canonical last error field`() {
        val originalError = TestDataLoomError(
            code = ErrorCode("NETWORK_TEMPORARY"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.WARNING,
            recoverability = Recoverability.RECOVERABLE,
            message = "A temporary network failure occurred.",
        )
        val original = QueueEntry(
            id = QueueEntryId("retry-with-error"),
            synchronizationRequest = request(),
            state = QueueEntryState.RETRY_WAITING,
            enqueuedAt = DataLoomInstant(1_000L),
            availableAt = DataLoomInstant(2_000L),
            retryAttempt = RetryAttempt(2),
            lastError = originalError,
        )

        val entity = original.toEntity()
        val restoredError = checkNotNull(entity.toDomain().lastError)

        assertEquals(originalError.code, restoredError.code)
        assertEquals(originalError.category, restoredError.category)
        assertEquals(originalError.severity, restoredError.severity)
        assertEquals(originalError.recoverability, restoredError.recoverability)
        assertEquals(originalError.message, restoredError.message)
        assertNull(restoredError.cause)
    }

    @Test
    fun `partially populated canonical error fails closed`() {
        val original = QueueEntry(
            id = QueueEntryId("corrupt-error"),
            synchronizationRequest = request(),
            state = QueueEntryState.RETRY_WAITING,
            enqueuedAt = DataLoomInstant(1_000L),
            availableAt = DataLoomInstant(2_000L),
            retryAttempt = RetryAttempt(1),
            lastError = TestDataLoomError(
                code = ErrorCode("STORAGE_TEMPORARY"),
                category = ErrorCategory.STORAGE,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "A temporary storage failure occurred.",
            ),
        )

        val corrupt = original.toEntity().copy(lastErrorCategory = null)

        assertFailsWith<IllegalStateException> {
            corrupt.toDomain()
        }
    }

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
