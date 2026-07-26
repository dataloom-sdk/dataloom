package io.dataloom.queue.room.internal

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
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
import kotlin.test.assertNull

/**
 * Unit tests for the [QueueEntry] ↔ [QueueEntryEntity] mapper functions.
 *
 * These tests verify round-trip fidelity for all supported states, optional
 * fields, and enum name preservation.
 */
class MappersTest {

    private fun makeMinimalEntry(
        id: String = "entry-1",
        state: QueueEntryState = QueueEntryState.PENDING,
        enqueuedAtMs: Long = 1_000_000L,
        availableAtMs: Long = 1_000_000L,
    ): QueueEntry {
        val ctx = ExecutionContext(
            executionId = ExecutionId("exec-1"),
            correlationId = CorrelationId("corr-1"),
        )
        val request = SynchronizationRequest(
            workflowId = WorkflowId("wf-1"),
            sessionId = SynchronizationSessionId("sess-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ctx,
        )
        return QueueEntry(
            id = QueueEntryId(id),
            synchronizationRequest = request,
            state = state,
            enqueuedAt = DataLoomInstant(enqueuedAtMs),
            availableAt = DataLoomInstant(availableAtMs),
        )
    }

    @Test
    fun `toEntity preserves entry ID`() {
        val entry = makeMinimalEntry(id = "my-entry-id")
        assertEquals("my-entry-id", entry.toEntity().entryId)
    }

    @Test
    fun `toEntity preserves workflow and session identifiers`() {
        val entity = makeMinimalEntry().toEntity()
        assertEquals("wf-1", entity.workflowId)
        assertEquals("sess-1", entity.sessionId)
    }

    @Test
    fun `toEntity preserves direction enum name`() {
        val entity = makeMinimalEntry().toEntity()
        assertEquals("PUSH", entity.direction)
    }

    @Test
    fun `toEntity preserves mode enum name`() {
        val entity = makeMinimalEntry().toEntity()
        assertEquals("DELTA", entity.mode)
    }

    @Test
    fun `toEntity preserves priority enum name`() {
        val entity = makeMinimalEntry().toEntity()
        assertEquals("NORMAL", entity.priority)
    }

    @Test
    fun `toEntity preserves execution context identifiers`() {
        val entity = makeMinimalEntry().toEntity()
        assertEquals("exec-1", entity.execExecutionId)
        assertEquals("corr-1", entity.execCorrelationId)
    }

    @Test
    fun `toEntity stores null for absent optional context fields`() {
        val entity = makeMinimalEntry().toEntity()
        assertNull(entity.execTraceId)
        assertNull(entity.execRequestId)
        assertNull(entity.execTenantId)
        assertNull(entity.execUserId)
        assertNull(entity.execLocaleTag)
        assertNull(entity.execRuntimeVersion)
        assertNull(entity.execConfigVersion)
        assertNull(entity.execMetadataJson)
    }

    @Test
    fun `toEntity stores null for absent lease`() {
        val entity = makeMinimalEntry().toEntity()
        assertNull(entity.leaseId)
        assertNull(entity.leaseConsumerId)
        assertNull(entity.leaseAcquiredAtMs)
        assertNull(entity.leaseExpiresAtMs)
    }

    @Test
    fun `round-trip minimal PENDING entry preserves all fields`() {
        val original = makeMinimalEntry()
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original.id, roundTripped.id)
        assertEquals(original.state, roundTripped.state)
        assertEquals(original.enqueuedAt, roundTripped.enqueuedAt)
        assertEquals(original.availableAt, roundTripped.availableAt)
        assertEquals(original.synchronizationRequest.workflowId, roundTripped.synchronizationRequest.workflowId)
        assertEquals(original.synchronizationRequest.sessionId, roundTripped.synchronizationRequest.sessionId)
        assertEquals(original.synchronizationRequest.direction, roundTripped.synchronizationRequest.direction)
        assertEquals(original.synchronizationRequest.mode, roundTripped.synchronizationRequest.mode)
        assertEquals(original.synchronizationRequest.priority, roundTripped.synchronizationRequest.priority)
    }

    @Test
    fun `round-trip entry with full optional context fields`() {
        val ctx = ExecutionContext(
            executionId = ExecutionId("exec-full"),
            correlationId = CorrelationId("corr-full"),
            traceId = TraceId("trace-1"),
            requestId = RequestId("request-1"),
            tenantId = TenantId("tenant-1"),
            userId = UserId("user-1"),
            localeTag = LocaleTag("en-US"),
            runtimeVersion = RuntimeVersion("1.2.3"),
            configurationVersion = ConfigurationVersion("v2"),
            metadata = DataLoomMetadata.of(mapOf("key1" to "value1", "key2" to "value2")),
        )
        val request = SynchronizationRequest(
            workflowId = WorkflowId("wf-full"),
            sessionId = SynchronizationSessionId("sess-full"),
            direction = SynchronizationDirection.BIDIRECTIONAL,
            mode = SynchronizationMode.FULL,
            priority = WorkflowPriority.HIGH,
            context = ctx,
        )
        val entry = QueueEntry(
            id = QueueEntryId("full-entry"),
            synchronizationRequest = request,
            state = QueueEntryState.PENDING,
            enqueuedAt = DataLoomInstant(1_000_000L),
            availableAt = DataLoomInstant(1_000_000L),
            metadata = DataLoomMetadata.of(mapOf("entry-key" to "entry-value")),
        )

        val roundTripped = entry.toEntity().toDomain()

        assertEquals(entry.id, roundTripped.id)
        val origCtx = entry.synchronizationRequest.context
        val rtCtx = roundTripped.synchronizationRequest.context
        assertEquals(origCtx.executionId, rtCtx.executionId)
        assertEquals(origCtx.correlationId, rtCtx.correlationId)
        assertEquals(origCtx.traceId, rtCtx.traceId)
        assertEquals(origCtx.requestId, rtCtx.requestId)
        assertEquals(origCtx.tenantId, rtCtx.tenantId)
        assertEquals(origCtx.userId, rtCtx.userId)
        assertEquals(origCtx.localeTag, rtCtx.localeTag)
        assertEquals(origCtx.runtimeVersion, rtCtx.runtimeVersion)
        assertEquals(origCtx.configurationVersion, rtCtx.configurationVersion)
        assertEquals(origCtx.metadata, rtCtx.metadata)
        assertEquals(entry.metadata, roundTripped.metadata)
    }

    @Test
    fun `round-trip RETRY_WAITING entry preserves retry attempt number`() {
        val ctx = ExecutionContext(
            executionId = ExecutionId("exec-1"),
            correlationId = CorrelationId("corr-1"),
        )
        val request = SynchronizationRequest(
            workflowId = WorkflowId("wf-1"),
            sessionId = SynchronizationSessionId("sess-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ctx,
        )
        val entry = QueueEntry(
            id = QueueEntryId("retry-entry"),
            synchronizationRequest = request,
            state = QueueEntryState.RETRY_WAITING,
            enqueuedAt = DataLoomInstant(1_000_000L),
            availableAt = DataLoomInstant(2_000_000L),
            retryAttempt = RetryAttempt(3),
        )

        val roundTripped = entry.toEntity().toDomain()

        assertEquals(3, roundTripped.retryAttempt?.number)
        assertEquals(QueueEntryState.RETRY_WAITING, roundTripped.state)
    }
}
