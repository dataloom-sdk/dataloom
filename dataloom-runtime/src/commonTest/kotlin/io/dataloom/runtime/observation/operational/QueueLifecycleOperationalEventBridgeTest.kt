package io.dataloom.runtime.observation.operational

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueueEntryExecutionOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [QueueLifecycleOperationalEventBridge] maps a
 * [QueueEntryExecutionOutcome] to a sane
 * [io.dataloom.api.operational.OperationalEventEnvelope], and that field
 * classification never leaks a raw sensitive value.
 */
class QueueLifecycleOperationalEventBridgeTest {

    private val t0 = DataLoomInstant(1_000_000L)
    private val t2 = DataLoomInstant(3_000_000L)
    private val witnessedAt = DataLoomInstant(9_000_000L)

    private val sampleContext = ExecutionContext(
        executionId = ExecutionId("exec-001"),
        correlationId = CorrelationId("corr-001"),
        traceId = TraceId("trace-001"),
        tenantId = TenantId("tenant-001"),
    )

    private val sampleRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-should-be-masked"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.FULL,
        priority = WorkflowPriority.HIGH,
        context = sampleContext,
    )

    private fun entry(
        id: QueueEntryId = QueueEntryId("entry-001"),
        lease: QueueLease = QueueLease(
            id = QueueLeaseId("lease-should-be-masked"),
            consumerId = QueueConsumerId("consumer-001"),
            acquiredAt = t0,
            expiresAt = t2,
        ),
        retryAttempt: RetryAttempt? = null,
        request: SynchronizationRequest = sampleRequest,
    ): QueueEntry = QueueEntry(
        id = id,
        synchronizationRequest = request,
        state = QueueEntryState.LEASED,
        enqueuedAt = t0,
        availableAt = t0,
        retryAttempt = retryAttempt,
        lease = lease,
    )

    private val sampleError: DataLoomError = FakeError()

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-Q-FAKE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-error-message-should-be-removed",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // -------------------------------------------------------------------------
    // Identity/routing
    // -------------------------------------------------------------------------

    @Test
    fun toEnvelope_reusesRequestContext_forCorrelationTraceAndTenant_neverInventsThem() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Completed(t2),
            witnessedAt,
        )
        assertEquals(CorrelationId("corr-001"), envelope.correlationId)
        assertEquals(TraceId("trace-001"), envelope.traceId)
        assertEquals(TenantId("tenant-001"), envelope.tenantId)
        assertEquals(WorkflowId("workflow-001"), envelope.workflowId)
    }

    @Test
    fun toEnvelope_completed_reusesCompletedAt_neverUsesWitnessedAt() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Completed(t2),
            witnessedAt,
        )
        assertEquals(t2, envelope.occurredAt)
    }

    @Test
    fun toEnvelope_reschedule_usesWitnessedAt_asOccurredAt() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Reschedule(RetryAttempt(1), t2, sampleError),
            witnessedAt,
        )
        assertEquals(witnessedAt, envelope.occurredAt)
    }

    @Test
    fun toEnvelope_failed_usesWitnessedAt_asOccurredAt() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Failed(sampleError, QueueFailureDisposition.FAILED),
            witnessedAt,
        )
        assertEquals(witnessedAt, envelope.occurredAt)
    }

    @Test
    fun toEnvelope_category_isLifecycle() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Completed(t2),
            witnessedAt,
        )
        assertEquals(OperationalEventCategory.LIFECYCLE, envelope.category)
    }

    @Test
    fun toEnvelope_typeReflectsOutcomeKind() {
        val e = entry()
        assertEquals(
            "dataloom.queue.entry.completed",
            QueueLifecycleOperationalEventBridge.toEnvelope(
                e,
                e.lease!!.id,
                QueueEntryExecutionOutcome.Completed(t2),
                witnessedAt,
            ).type.value,
        )
        assertEquals(
            "dataloom.queue.entry.rescheduled",
            QueueLifecycleOperationalEventBridge.toEnvelope(
                e,
                e.lease!!.id,
                QueueEntryExecutionOutcome.Reschedule(RetryAttempt(1), t2, sampleError),
                witnessedAt,
            ).type.value,
        )
        assertEquals(
            "dataloom.queue.entry.deferred",
            QueueLifecycleOperationalEventBridge.toEnvelope(
                e,
                e.lease!!.id,
                QueueEntryExecutionOutcome.Deferred(t2, QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET),
                witnessedAt,
            ).type.value,
        )
        assertEquals(
            "dataloom.queue.entry.failed",
            QueueLifecycleOperationalEventBridge.toEnvelope(
                e,
                e.lease!!.id,
                QueueEntryExecutionOutcome.Failed(sampleError, QueueFailureDisposition.FAILED),
                witnessedAt,
            ).type.value,
        )
        assertEquals(
            "dataloom.queue.entry.cancelled",
            QueueLifecycleOperationalEventBridge.toEnvelope(
                e,
                e.lease!!.id,
                QueueEntryExecutionOutcome.Cancelled(sampleContext),
                witnessedAt,
            ).type.value,
        )
    }

    @Test
    fun toEnvelope_sanitizesDisallowedCharacters_inEntryIdAndLeaseId() {
        val e = entry(
            id = QueueEntryId("entry id 1!#weird"),
            lease = QueueLease(
                id = QueueLeaseId("lease id 2!#weird"),
                consumerId = QueueConsumerId("consumer-001"),
                acquiredAt = t0,
                expiresAt = t2,
            ),
        )
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Completed(t2),
            witnessedAt,
        )
        assertTrue(envelope.id.value.none { it == ' ' || it == '!' || it == '#' })
    }

    @Test
    fun toEnvelope_isPureAndDeterministic() {
        val e = entry()
        val outcome = QueueEntryExecutionOutcome.Completed(t2)
        val first = QueueLifecycleOperationalEventBridge.toEnvelope(e, e.lease!!.id, outcome, witnessedAt)
        val second = QueueLifecycleOperationalEventBridge.toEnvelope(e, e.lease!!.id, outcome, witnessedAt)
        assertEquals(first, second)
    }

    @Test
    fun toEnvelope_differentLeaseId_producesDifferentEnvelopeId_forSameEntry() {
        val e = entry(id = QueueEntryId("entry-repeat"))
        val firstEnvelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            QueueLeaseId("lease-attempt-1"),
            QueueEntryExecutionOutcome.Reschedule(RetryAttempt(1), t2, sampleError),
            witnessedAt,
        )
        val secondEnvelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            QueueLeaseId("lease-attempt-2"),
            QueueEntryExecutionOutcome.Completed(t2),
            witnessedAt,
        )
        assertNotEquals(firstEnvelope.id, secondEnvelope.id)
    }

    // -------------------------------------------------------------------------
    // Resulting-state derivation
    // -------------------------------------------------------------------------

    @Test
    fun toEnvelope_resultingState_completed_isCompleted() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Completed(t2),
            witnessedAt,
        )
        assertEquals("COMPLETED", envelope.attributes["transition.resultingState"])
    }

    @Test
    fun toEnvelope_resultingState_reschedule_isRetryWaiting() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Reschedule(RetryAttempt(1), t2, sampleError),
            witnessedAt,
        )
        assertEquals("RETRY_WAITING", envelope.attributes["transition.resultingState"])
    }

    @Test
    fun toEnvelope_resultingState_deferredWithoutPriorAttempt_isPending() {
        val e = entry(retryAttempt = null)
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Deferred(t2, QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET),
            witnessedAt,
        )
        assertEquals(QueueEntryState.PENDING.name, envelope.attributes["transition.resultingState"])
    }

    @Test
    fun toEnvelope_resultingState_deferredWithPriorAttempt_isRetryWaiting() {
        val e = entry(retryAttempt = RetryAttempt(2))
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Deferred(t2, QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET),
            witnessedAt,
        )
        assertEquals(QueueEntryState.RETRY_WAITING.name, envelope.attributes["transition.resultingState"])
    }

    @Test
    fun toEnvelope_resultingState_failedDisposition_isFailed() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Failed(sampleError, QueueFailureDisposition.FAILED),
            witnessedAt,
        )
        assertEquals(QueueEntryState.FAILED.name, envelope.attributes["transition.resultingState"])
    }

    @Test
    fun toEnvelope_resultingState_deadLetterDisposition_isDeadLetter() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Failed(sampleError, QueueFailureDisposition.DEAD_LETTER),
            witnessedAt,
        )
        assertEquals(QueueEntryState.DEAD_LETTER.name, envelope.attributes["transition.resultingState"])
    }

    @Test
    fun toEnvelope_resultingState_cancelled_isCancelled() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Cancelled(sampleContext),
            witnessedAt,
        )
        assertEquals(QueueEntryState.CANCELLED.name, envelope.attributes["transition.resultingState"])
    }

    // -------------------------------------------------------------------------
    // Classification
    // -------------------------------------------------------------------------

    @Test
    fun toEnvelope_masksSessionIdAndLeaseId_neverKeepsRawValue() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Completed(t2),
            witnessedAt,
        )
        assertNotRawValue("session-should-be-masked", envelope.attributes["entry.sessionId"])
        assertNotRawValue("lease-should-be-masked", envelope.attributes["entry.leaseId"])
    }

    @Test
    fun toEnvelope_removesErrorMessage_keepsErrorCodeCategorySeverityRecoverability() {
        val e = entry()
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Failed(sampleError, QueueFailureDisposition.FAILED),
            witnessedAt,
        )
        assertNull(envelope.attributes["transition.error.message"])
        assertEquals("DL-Q-FAKE", envelope.attributes["transition.error.code"])
        assertEquals("PROVIDER", envelope.attributes["transition.error.category"])
        assertEquals("ERROR", envelope.attributes["transition.error.severity"])
        assertEquals("RECOVERABLE", envelope.attributes["transition.error.recoverability"])
    }

    @Test
    fun toEnvelope_keepsEnumAndCounterFields() {
        val e = entry(retryAttempt = RetryAttempt(3))
        val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
            e,
            e.lease!!.id,
            QueueEntryExecutionOutcome.Completed(t2),
            witnessedAt,
        )
        assertEquals("PUSH", envelope.attributes["entry.direction"])
        assertEquals("FULL", envelope.attributes["entry.mode"])
        assertEquals("HIGH", envelope.attributes["entry.priority"])
        assertEquals("3", envelope.attributes["entry.retryAttemptNumber"])
        assertEquals("COMPLETED", envelope.attributes["transition.kind"])
    }

    private fun assertNotRawValue(rawValue: String, redactedValue: String?) {
        assertTrue(redactedValue != null && redactedValue != rawValue, "Expected redaction of '$rawValue'.")
    }
}
