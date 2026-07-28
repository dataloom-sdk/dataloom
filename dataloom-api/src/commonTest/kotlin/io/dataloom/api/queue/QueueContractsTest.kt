package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
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
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueContractsTest {

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private val entryId = QueueEntryId("entry-001")
    private val leaseId = QueueLeaseId("lease-001")
    private val consumerId = QueueConsumerId("consumer-001")

    private val t0 = DataLoomInstant(1_000_000L)
    private val t1 = DataLoomInstant(2_000_000L)
    private val t2 = DataLoomInstant(3_000_000L)

    private val sampleLease = QueueLease(
        id = leaseId,
        consumerId = consumerId,
        acquiredAt = t0,
        expiresAt = t1,
    )

    private val sampleRequest: SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("exec-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private val sampleError: DataLoomError = TestDataLoomError(
        code = ErrorCode("DL-QUEUE-001"),
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "Queue processing failed.",
        cause = null,
    )

    private val sampleContext = ExecutionContext(
        executionId = ExecutionId("exec-cancel-001"),
        correlationId = CorrelationId("corr-cancel-001"),
    )

    private fun pendingEntry(
        id: QueueEntryId = entryId,
        enqueuedAt: DataLoomInstant = t0,
        availableAt: DataLoomInstant = t0,
    ): QueueEntry = QueueEntry(
        id = id,
        synchronizationRequest = sampleRequest,
        state = QueueEntryState.PENDING,
        enqueuedAt = enqueuedAt,
        availableAt = availableAt,
    )

    private fun leasedEntry(lease: QueueLease = sampleLease): QueueEntry = QueueEntry(
        id = entryId,
        synchronizationRequest = sampleRequest,
        state = QueueEntryState.LEASED,
        enqueuedAt = t0,
        availableAt = t0,
        lease = lease,
    )

    // -------------------------------------------------------------------------
    // QueueEntryId
    // -------------------------------------------------------------------------

    @Test
    fun `QueueEntryId preserves valid value`() {
        val id = QueueEntryId("entry-abc")
        assertEquals("entry-abc", id.value)
    }

    @Test
    fun `QueueEntryId toString returns value`() {
        val id = QueueEntryId("entry-abc")
        assertEquals("entry-abc", id.toString())
    }

    @Test
    fun `QueueEntryId rejects blank value`() {
        assertFailsWith<IllegalArgumentException> { QueueEntryId("") }
        assertFailsWith<IllegalArgumentException> { QueueEntryId("   ") }
    }

    @Test
    fun `equal QueueEntryId values compare as equal`() {
        assertEquals(QueueEntryId("entry-1"), QueueEntryId("entry-1"))
    }

    @Test
    fun `different QueueEntryId values compare as unequal`() {
        assertNotEquals(QueueEntryId("entry-1"), QueueEntryId("entry-2"))
    }

    // -------------------------------------------------------------------------
    // QueueLeaseId
    // -------------------------------------------------------------------------

    @Test
    fun `QueueLeaseId preserves valid value`() {
        val id = QueueLeaseId("lease-xyz")
        assertEquals("lease-xyz", id.value)
    }

    @Test
    fun `QueueLeaseId toString returns value`() {
        assertEquals("lease-xyz", QueueLeaseId("lease-xyz").toString())
    }

    @Test
    fun `QueueLeaseId rejects blank value`() {
        assertFailsWith<IllegalArgumentException> { QueueLeaseId("") }
        assertFailsWith<IllegalArgumentException> { QueueLeaseId("\t") }
    }

    @Test
    fun `equal QueueLeaseId values compare as equal`() {
        assertEquals(QueueLeaseId("lease-1"), QueueLeaseId("lease-1"))
    }

    // -------------------------------------------------------------------------
    // QueueConsumerId
    // -------------------------------------------------------------------------

    @Test
    fun `QueueConsumerId preserves valid value`() {
        val id = QueueConsumerId("consumer-xyz")
        assertEquals("consumer-xyz", id.value)
    }

    @Test
    fun `QueueConsumerId toString returns value`() {
        assertEquals("consumer-xyz", QueueConsumerId("consumer-xyz").toString())
    }

    @Test
    fun `QueueConsumerId rejects blank value`() {
        assertFailsWith<IllegalArgumentException> { QueueConsumerId("") }
        assertFailsWith<IllegalArgumentException> { QueueConsumerId("  ") }
    }

    @Test
    fun `equal QueueConsumerId values compare as equal`() {
        assertEquals(QueueConsumerId("consumer-1"), QueueConsumerId("consumer-1"))
    }

    // -------------------------------------------------------------------------
    // RetryAttempt (minimal)
    // -------------------------------------------------------------------------

    @Test
    fun `RetryAttempt number one is valid`() {
        val attempt = RetryAttempt(1)
        assertEquals(1, attempt.number)
    }

    @Test
    fun `RetryAttempt count zero is rejected`() {
        assertFailsWith<IllegalArgumentException> { RetryAttempt(0) }
    }

    @Test
    fun `RetryAttempt negative count is rejected`() {
        assertFailsWith<IllegalArgumentException> { RetryAttempt(-1) }
    }

    @Test
    fun `equal RetryAttempt values compare as equal`() {
        assertEquals(RetryAttempt(3), RetryAttempt(3))
    }

    @Test
    fun `different RetryAttempt values compare as unequal`() {
        assertNotEquals(RetryAttempt(1), RetryAttempt(2))
    }

    // -------------------------------------------------------------------------
    // QueueEntryState
    // -------------------------------------------------------------------------

    @Test
    fun `QueueEntryState exposes all required values`() {
        val names = QueueEntryState.entries.map { it.name }.toSet()
        val expected = setOf("PENDING", "LEASED", "RETRY_WAITING", "COMPLETED", "FAILED", "CANCELLED", "DEAD_LETTER")
        assertEquals(expected, names)
    }

    // -------------------------------------------------------------------------
    // QueueLease
    // -------------------------------------------------------------------------

    @Test
    fun `QueueLease preserves all properties`() {
        val lease = QueueLease(
            id = leaseId,
            consumerId = consumerId,
            acquiredAt = t0,
            expiresAt = t1,
        )
        assertEquals(leaseId, lease.id)
        assertEquals(consumerId, lease.consumerId)
        assertEquals(t0, lease.acquiredAt)
        assertEquals(t1, lease.expiresAt)
    }

    @Test
    fun `QueueLease expiresAt after acquiredAt is accepted`() {
        val lease = QueueLease(id = leaseId, consumerId = consumerId, acquiredAt = t0, expiresAt = t1)
        assertEquals(t1, lease.expiresAt)
    }

    @Test
    fun `QueueLease equal expiresAt and acquiredAt is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueLease(id = leaseId, consumerId = consumerId, acquiredAt = t0, expiresAt = t0)
        }
    }

    @Test
    fun `QueueLease expiresAt before acquiredAt is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueLease(id = leaseId, consumerId = consumerId, acquiredAt = t1, expiresAt = t0)
        }
    }

    @Test
    fun `equal QueueLease values compare as equal`() {
        val a = QueueLease(id = leaseId, consumerId = consumerId, acquiredAt = t0, expiresAt = t1)
        val b = QueueLease(id = leaseId, consumerId = consumerId, acquiredAt = t0, expiresAt = t1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different QueueLease values compare as unequal`() {
        val a = QueueLease(id = leaseId, consumerId = consumerId, acquiredAt = t0, expiresAt = t1)
        val b = QueueLease(id = QueueLeaseId("lease-002"), consumerId = consumerId, acquiredAt = t0, expiresAt = t1)
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // QueueEntry — construction and state invariants
    // -------------------------------------------------------------------------

    @Test
    fun `PENDING entry is constructed successfully`() {
        val entry = pendingEntry()
        assertEquals(QueueEntryState.PENDING, entry.state)
        assertNull(entry.lease)
        assertNull(entry.retryAttempt)
        assertNull(entry.lastError)
        assertEquals(DataLoomMetadata.Empty, entry.metadata)
    }

    @Test
    fun `LEASED entry requires non-null lease`() {
        val entry = leasedEntry()
        assertEquals(QueueEntryState.LEASED, entry.state)
        assertEquals(sampleLease, entry.lease)
    }

    @Test
    fun `LEASED entry with null lease is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueEntry(
                id = entryId,
                synchronizationRequest = sampleRequest,
                state = QueueEntryState.LEASED,
                enqueuedAt = t0,
                availableAt = t0,
                lease = null,
            )
        }
    }

    @Test
    fun `PENDING entry with non-null lease is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueEntry(
                id = entryId,
                synchronizationRequest = sampleRequest,
                state = QueueEntryState.PENDING,
                enqueuedAt = t0,
                availableAt = t0,
                lease = sampleLease,
            )
        }
    }

    @Test
    fun `RETRY_WAITING entry requires non-null retryAttempt`() {
        val entry = QueueEntry(
            id = entryId,
            synchronizationRequest = sampleRequest,
            state = QueueEntryState.RETRY_WAITING,
            enqueuedAt = t0,
            availableAt = t1,
            retryAttempt = RetryAttempt(1),
        )
        assertEquals(RetryAttempt(1), entry.retryAttempt)
    }

    @Test
    fun `RETRY_WAITING entry with null retryAttempt is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueEntry(
                id = entryId,
                synchronizationRequest = sampleRequest,
                state = QueueEntryState.RETRY_WAITING,
                enqueuedAt = t0,
                availableAt = t1,
                retryAttempt = null,
            )
        }
    }

    @Test
    fun `PENDING entry with non-null retryAttempt is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueEntry(
                id = entryId,
                synchronizationRequest = sampleRequest,
                state = QueueEntryState.PENDING,
                enqueuedAt = t0,
                availableAt = t0,
                retryAttempt = RetryAttempt(1),
            )
        }
    }

    @Test
    fun `availableAt before enqueuedAt is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueEntry(
                id = entryId,
                synchronizationRequest = sampleRequest,
                state = QueueEntryState.PENDING,
                enqueuedAt = t1,
                availableAt = t0,
            )
        }
    }

    @Test
    fun `availableAt equal to enqueuedAt is accepted`() {
        val entry = pendingEntry(enqueuedAt = t0, availableAt = t0)
        assertEquals(t0, entry.availableAt)
    }

    @Test
    fun `availableAt after enqueuedAt is accepted`() {
        val entry = pendingEntry(enqueuedAt = t0, availableAt = t1)
        assertEquals(t1, entry.availableAt)
    }

    @Test
    fun `COMPLETED entry with no lease is accepted`() {
        val entry = QueueEntry(
            id = entryId,
            synchronizationRequest = sampleRequest,
            state = QueueEntryState.COMPLETED,
            enqueuedAt = t0,
            availableAt = t0,
        )
        assertEquals(QueueEntryState.COMPLETED, entry.state)
        assertNull(entry.lease)
    }

    @Test
    fun `FAILED entry may carry lastError`() {
        val entry = QueueEntry(
            id = entryId,
            synchronizationRequest = sampleRequest,
            state = QueueEntryState.FAILED,
            enqueuedAt = t0,
            availableAt = t0,
            lastError = sampleError,
        )
        assertEquals(sampleError, entry.lastError)
    }

    @Test
    fun `DEAD_LETTER entry may carry lastError`() {
        val entry = QueueEntry(
            id = entryId,
            synchronizationRequest = sampleRequest,
            state = QueueEntryState.DEAD_LETTER,
            enqueuedAt = t0,
            availableAt = t0,
            lastError = sampleError,
        )
        assertEquals(sampleError, entry.lastError)
    }

    @Test
    fun `metadata defaults to empty`() {
        val entry = pendingEntry()
        assertEquals(DataLoomMetadata.Empty, entry.metadata)
    }

    @Test
    fun `metadata is preserved when supplied`() {
        val meta = DataLoomMetadata.of(mapOf("key" to "value"))
        val entry = QueueEntry(
            id = entryId,
            synchronizationRequest = sampleRequest,
            state = QueueEntryState.PENDING,
            enqueuedAt = t0,
            availableAt = t0,
            metadata = meta,
        )
        assertEquals("value", entry.metadata["key"])
    }

    @Test
    fun `equal QueueEntry values compare as equal`() {
        val a = pendingEntry()
        val b = pendingEntry()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different QueueEntry ids compare as unequal`() {
        val a = pendingEntry(id = QueueEntryId("entry-001"))
        val b = pendingEntry(id = QueueEntryId("entry-002"))
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // QueueEnqueueRequest
    // -------------------------------------------------------------------------

    @Test
    fun `QueueEnqueueRequest accepts PENDING entry`() {
        val req = QueueEnqueueRequest(entry = pendingEntry())
        assertEquals(QueueEntryState.PENDING, req.entry.state)
    }

    @Test
    fun `QueueEnqueueRequest rejects non-PENDING entry`() {
        assertFailsWith<IllegalArgumentException> {
            QueueEnqueueRequest(entry = leasedEntry())
        }
    }

    @Test
    fun `QueueEnqueueRequest rejects entry with lease`() {
        assertFailsWith<IllegalArgumentException> {
            QueueEnqueueRequest(
                entry = QueueEntry(
                    id = entryId,
                    synchronizationRequest = sampleRequest,
                    state = QueueEntryState.PENDING,
                    enqueuedAt = t0,
                    availableAt = t0,
                    lease = sampleLease,
                ),
            )
        }
    }

    @Test
    fun `QueueEnqueueRequest rejects entry with retryAttempt`() {
        assertFailsWith<IllegalArgumentException> {
            QueueEnqueueRequest(
                entry = QueueEntry(
                    id = entryId,
                    synchronizationRequest = sampleRequest,
                    state = QueueEntryState.PENDING,
                    enqueuedAt = t0,
                    availableAt = t0,
                    retryAttempt = RetryAttempt(1),
                ),
            )
        }
    }

    // -------------------------------------------------------------------------
    // QueueAcquireRequest
    // -------------------------------------------------------------------------

    @Test
    fun `QueueAcquireRequest preserves all properties`() {
        val req = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 5,
        )
        assertEquals(consumerId, req.consumerId)
        assertEquals(leaseId, req.leaseId)
        assertEquals(t0, req.acquiredAt)
        assertEquals(t1, req.leaseExpiresAt)
        assertEquals(5, req.maxEntries)
        assertEquals(DataLoomMetadata.Empty, req.metadata)
    }

    @Test
    fun `QueueAcquireRequest leaseExpiresAt equal to acquiredAt is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueAcquireRequest(
                consumerId = consumerId,
                leaseId = leaseId,
                acquiredAt = t0,
                leaseExpiresAt = t0,
                maxEntries = 1,
            )
        }
    }

    @Test
    fun `QueueAcquireRequest leaseExpiresAt before acquiredAt is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueAcquireRequest(
                consumerId = consumerId,
                leaseId = leaseId,
                acquiredAt = t1,
                leaseExpiresAt = t0,
                maxEntries = 1,
            )
        }
    }

    @Test
    fun `QueueAcquireRequest maxEntries zero is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueAcquireRequest(
                consumerId = consumerId,
                leaseId = leaseId,
                acquiredAt = t0,
                leaseExpiresAt = t1,
                maxEntries = 0,
            )
        }
    }

    @Test
    fun `QueueAcquireRequest maxEntries negative is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            QueueAcquireRequest(
                consumerId = consumerId,
                leaseId = leaseId,
                acquiredAt = t0,
                leaseExpiresAt = t1,
                maxEntries = -1,
            )
        }
    }

    @Test
    fun `QueueAcquireRequest maxEntries one is accepted`() {
        val req = QueueAcquireRequest(
            consumerId = consumerId,
            leaseId = leaseId,
            acquiredAt = t0,
            leaseExpiresAt = t1,
            maxEntries = 1,
        )
        assertEquals(1, req.maxEntries)
    }

    // -------------------------------------------------------------------------
    // QueueAcquireResult
    // -------------------------------------------------------------------------

    @Test
    fun `QueueAcquireResult NoEntries is a data object`() {
        val result: QueueAcquireResult = QueueAcquireResult.NoEntries
        assertIs<QueueAcquireResult.NoEntries>(result)
    }

    @Test
    fun `QueueAcquireResult Entries preserves lease and entries`() {
        val entry = leasedEntry(sampleLease)
        val result = QueueAcquireResult.Entries(
            lease = sampleLease,
            entries = listOf(entry),
        )
        assertEquals(sampleLease, result.lease)
        assertEquals(listOf(entry), result.entries)
    }

    @Test
    fun `QueueAcquireResult Entries rejects empty list`() {
        assertFailsWith<IllegalArgumentException> {
            QueueAcquireResult.Entries(
                lease = sampleLease,
                entries = emptyList(),
            )
        }
    }

    @Test
    fun `QueueAcquireResult Entries rejects non-LEASED entry`() {
        assertFailsWith<IllegalArgumentException> {
            QueueAcquireResult.Entries(
                lease = sampleLease,
                entries = listOf(pendingEntry()),
            )
        }
    }

    @Test
    fun `QueueAcquireResult Entries rejects entry with mismatched lease`() {
        val otherLease = QueueLease(
            id = QueueLeaseId("lease-other"),
            consumerId = consumerId,
            acquiredAt = t0,
            expiresAt = t1,
        )
        val entryWithOtherLease = leasedEntry(otherLease)
        assertFailsWith<IllegalArgumentException> {
            QueueAcquireResult.Entries(
                lease = sampleLease,
                entries = listOf(entryWithOtherLease),
            )
        }
    }

    @Test
    fun `QueueAcquireResult Entries defensively copies source list`() {
        val entry = leasedEntry(sampleLease)
        val source = mutableListOf(entry)
        val result = QueueAcquireResult.Entries(lease = sampleLease, entries = source)
        source.clear()
        assertEquals(1, result.entries.size)
    }

    @Test
    fun `QueueAcquireResult Entries exposes list typed as List not MutableList`() {
        val entry = leasedEntry(sampleLease)
        val result = QueueAcquireResult.Entries(lease = sampleLease, entries = listOf(entry))
        // The public property type is List<QueueEntry>, which does not expose mutation methods.
        val list: List<QueueEntry> = result.entries
        assertEquals(1, list.size)
    }

    @Test
    fun `equal QueueAcquireResult Entries compare as equal`() {
        val entry = leasedEntry(sampleLease)
        val a = QueueAcquireResult.Entries(lease = sampleLease, entries = listOf(entry))
        val b = QueueAcquireResult.Entries(lease = sampleLease, entries = listOf(entry))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // -------------------------------------------------------------------------
    // QueueCompletionRequest
    // -------------------------------------------------------------------------

    @Test
    fun `QueueCompletionRequest preserves all properties`() {
        val req = QueueCompletionRequest(
            entryId = entryId,
            leaseId = leaseId,
            completedAt = t1,
        )
        assertEquals(entryId, req.entryId)
        assertEquals(leaseId, req.leaseId)
        assertEquals(t1, req.completedAt)
        assertEquals(DataLoomMetadata.Empty, req.metadata)
    }

    @Test
    fun `QueueCompletionRequest metadata defaults to empty`() {
        val req = QueueCompletionRequest(entryId = entryId, leaseId = leaseId, completedAt = t1)
        assertEquals(DataLoomMetadata.Empty, req.metadata)
    }

    // -------------------------------------------------------------------------
    // QueueRescheduleRequest
    // -------------------------------------------------------------------------

    @Test
    fun `QueueRescheduleRequest preserves all properties`() {
        val retryAttempt = RetryAttempt(2)
        val req = QueueRescheduleRequest(
            entryId = entryId,
            leaseId = leaseId,
            retryAttempt = retryAttempt,
            availableAt = t2,
            error = sampleError,
        )
        assertEquals(entryId, req.entryId)
        assertEquals(leaseId, req.leaseId)
        assertEquals(retryAttempt, req.retryAttempt)
        assertEquals(t2, req.availableAt)
        assertEquals(sampleError, req.error)
        assertEquals(DataLoomMetadata.Empty, req.metadata)
    }

    // -------------------------------------------------------------------------
    // QueueDeferralRequest
    // -------------------------------------------------------------------------

    @Test
    fun `QueueDeferralRequest preserves non-retry transition properties`() {
        val request = QueueDeferralRequest(
            entryId = entryId,
            leaseId = leaseId,
            availableAt = t2,
            reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
        )

        assertEquals(entryId, request.entryId)
        assertEquals(leaseId, request.leaseId)
        assertEquals(t2, request.availableAt)
        assertEquals(QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET, request.reason)
        assertEquals(DataLoomMetadata.Empty, request.metadata)
    }

    @Test
    fun `QueueDeferralReason exposes stable connectivity requirement name`() {
        assertEquals(
            setOf("CONNECTIVITY_REQUIREMENT_NOT_MET"),
            QueueDeferralReason.entries.map { it.name }.toSet(),
        )
    }

    // -------------------------------------------------------------------------
    // QueueFailureDisposition
    // -------------------------------------------------------------------------

    @Test
    fun `QueueFailureDisposition exposes FAILED and DEAD_LETTER`() {
        val names = QueueFailureDisposition.entries.map { it.name }.toSet()
        assertEquals(setOf("FAILED", "DEAD_LETTER"), names)
    }

    // -------------------------------------------------------------------------
    // QueueFailureRequest
    // -------------------------------------------------------------------------

    @Test
    fun `QueueFailureRequest preserves all properties`() {
        val req = QueueFailureRequest(
            entryId = entryId,
            leaseId = leaseId,
            error = sampleError,
            disposition = QueueFailureDisposition.FAILED,
        )
        assertEquals(entryId, req.entryId)
        assertEquals(leaseId, req.leaseId)
        assertEquals(sampleError, req.error)
        assertEquals(QueueFailureDisposition.FAILED, req.disposition)
        assertEquals(DataLoomMetadata.Empty, req.metadata)
    }

    @Test
    fun `QueueFailureRequest disposition DEAD_LETTER is accepted`() {
        val req = QueueFailureRequest(
            entryId = entryId,
            leaseId = leaseId,
            error = sampleError,
            disposition = QueueFailureDisposition.DEAD_LETTER,
        )
        assertEquals(QueueFailureDisposition.DEAD_LETTER, req.disposition)
    }

    // -------------------------------------------------------------------------
    // QueueCancellationRequest
    // -------------------------------------------------------------------------

    @Test
    fun `QueueCancellationRequest preserves all properties`() {
        val req = QueueCancellationRequest(
            entryId = entryId,
            context = sampleContext,
        )
        assertEquals(entryId, req.entryId)
        assertEquals(sampleContext, req.context)
        assertEquals(DataLoomMetadata.Empty, req.metadata)
    }

    // -------------------------------------------------------------------------
    // ExpiredLeaseRecoveryRequest
    // -------------------------------------------------------------------------

    @Test
    fun `ExpiredLeaseRecoveryRequest preserves currentTime`() {
        val req = ExpiredLeaseRecoveryRequest(currentTime = t0)
        assertEquals(t0, req.currentTime)
        assertEquals(DataLoomMetadata.Empty, req.metadata)
    }

    // -------------------------------------------------------------------------
    // ExpiredLeaseRecoveryResult
    // -------------------------------------------------------------------------

    @Test
    fun `ExpiredLeaseRecoveryResult zero is accepted`() {
        val result = ExpiredLeaseRecoveryResult(0)
        assertEquals(0, result.recoveredEntries)
    }

    @Test
    fun `ExpiredLeaseRecoveryResult positive count is accepted`() {
        val result = ExpiredLeaseRecoveryResult(5)
        assertEquals(5, result.recoveredEntries)
    }

    @Test
    fun `ExpiredLeaseRecoveryResult negative count is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExpiredLeaseRecoveryResult(-1)
        }
    }

    @Test
    fun `equal ExpiredLeaseRecoveryResult values compare as equal`() {
        assertEquals(ExpiredLeaseRecoveryResult(3), ExpiredLeaseRecoveryResult(3))
    }

    @Test
    fun `different ExpiredLeaseRecoveryResult values compare as unequal`() {
        assertNotEquals(ExpiredLeaseRecoveryResult(1), ExpiredLeaseRecoveryResult(2))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
