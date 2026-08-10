package io.dataloom.testing.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.testing.provider.TestProviderLifecycleController

/**
 * In-memory [QueueProvider] with a deterministic queue state machine.
 *
 * The provider preserves enqueue order with a [LinkedHashMap], records every
 * request, supports lease-based transitions, and allows cancellation of leased
 * entries for test convenience.
 *
 * Callers must serialize access externally. The implementation is mutable and
 * not thread-safe.
 *
 * @param descriptor provider descriptor exposed through [QueueProvider.descriptor].
 * @param lifecycleController shared lifecycle controller used by provider tests.
 */
public class InMemoryQueueProvider(
    override val descriptor: ProviderDescriptor = defaultDescriptor(),
    private val lifecycleController: TestProviderLifecycleController = TestProviderLifecycleController(),
) : QueueProvider {
    private val entries: LinkedHashMap<QueueEntryId, QueueEntry> = LinkedHashMap()
    private val recordedEnqueueRequests: MutableList<QueueEnqueueRequest> = mutableListOf()
    private val recordedAcquireRequests: MutableList<QueueAcquireRequest> = mutableListOf()
    private val recordedCompletionRequests: MutableList<QueueCompletionRequest> = mutableListOf()
    private val recordedRescheduleRequests: MutableList<QueueRescheduleRequest> = mutableListOf()
    private val recordedDeferralRequests: MutableList<QueueDeferralRequest> = mutableListOf()
    private val recordedFailureRequests: MutableList<QueueFailureRequest> = mutableListOf()
    private val recordedCancellationRequests: MutableList<QueueCancellationRequest> = mutableListOf()
    private val recordedRecoveryRequests: MutableList<ExpiredLeaseRecoveryRequest> = mutableListOf()

    /** Recorded enqueue requests in call order. */
    public val enqueueRequests: List<QueueEnqueueRequest>
        get() = recordedEnqueueRequests.toList()

    /** Recorded acquire requests in call order. */
    public val acquireRequests: List<QueueAcquireRequest>
        get() = recordedAcquireRequests.toList()

    /** Recorded completion requests in call order. */
    public val completionRequests: List<QueueCompletionRequest>
        get() = recordedCompletionRequests.toList()

    /** Recorded reschedule requests in call order. */
    public val rescheduleRequests: List<QueueRescheduleRequest>
        get() = recordedRescheduleRequests.toList()

    /** Recorded non-retry deferral requests in call order. */
    public val deferralRequests: List<QueueDeferralRequest>
        get() = recordedDeferralRequests.toList()

    /** Recorded failure requests in call order. */
    public val failureRequests: List<QueueFailureRequest>
        get() = recordedFailureRequests.toList()

    /** Recorded cancellation requests in call order. */
    public val cancellationRequests: List<QueueCancellationRequest>
        get() = recordedCancellationRequests.toList()

    /** Recorded expired-lease recovery requests in call order. */
    public val expiredLeaseRecoveryRequests: List<ExpiredLeaseRecoveryRequest>
        get() = recordedRecoveryRequests.toList()

    /** Number of entries currently stored in the in-memory queue. */
    public val entryCount: Int
        get() = entries.size

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = lifecycleController.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> = lifecycleController.health()

    override suspend fun close(): ProviderOperationResult<Unit> = lifecycleController.close()

    override suspend fun enqueue(
        request: QueueEnqueueRequest,
    ): ProviderOperationResult<Unit> {
        recordedEnqueueRequests += request
        if (entries.containsKey(request.entry.id)) {
            return ProviderOperationResult.Failure(
                InMemoryQueueError(
                    message = "Queue entry ${request.entry.id.value} already exists.",
                ),
            )
        }
        entries[request.entry.id] = request.entry.copy(
            state = QueueEntryState.PENDING,
            lease = null,
            retryAttempt = null,
            retryBudgetState = null,
            lastError = null,
        )
        return ProviderOperationResult.Success(Unit)
    }

    override suspend fun acquire(
        request: QueueAcquireRequest,
    ): ProviderOperationResult<QueueAcquireResult> {
        recordedAcquireRequests += request
        val lease = QueueLease(
            id = request.leaseId,
            consumerId = request.consumerId,
            acquiredAt = request.acquiredAt,
            expiresAt = request.leaseExpiresAt,
        )
        val eligibleIds = entries.values
            .mapIndexed { index, entry -> IndexedEntry(index = index, entry = entry) }
            .filter { indexedEntry -> indexedEntry.entry.isEligibleAt(request.acquiredAt.epochMilliseconds) }
            .sortedWith(compareBy<IndexedEntry> { it.entry.availableAt.epochMilliseconds }.thenBy { it.index })
            .take(request.maxEntries)
            .map { it.entry.id }

        if (eligibleIds.isEmpty()) {
            return ProviderOperationResult.Success(QueueAcquireResult.NoEntries)
        }

        val leasedEntries = eligibleIds.map { entryId ->
            val current = requireNotNull(entries[entryId])
            current.copy(
                state = QueueEntryState.LEASED,
                lease = lease,
                lastError = null,
            ).also { updated ->
                entries[entryId] = updated
            }
        }
        return ProviderOperationResult.Success(QueueAcquireResult.Entries(lease = lease, entries = leasedEntries))
    }

    override suspend fun complete(
        request: QueueCompletionRequest,
    ): ProviderOperationResult<Unit> {
        recordedCompletionRequests += request
        return withValidatedLease(request.entryId, request.leaseId) { entry ->
            entries[request.entryId] = entry.copy(
                state = QueueEntryState.COMPLETED,
                lease = null,
                lastError = null,
            )
            ProviderOperationResult.Success(Unit)
        }
    }

    override suspend fun reschedule(
        request: QueueRescheduleRequest,
    ): ProviderOperationResult<Unit> {
        recordedRescheduleRequests += request
        return withValidatedLease(request.entryId, request.leaseId) { entry ->
            entries[request.entryId] = entry.copy(
                state = QueueEntryState.RETRY_WAITING,
                availableAt = request.availableAt,
                retryAttempt = request.retryAttempt,
                retryBudgetState = request.retryBudgetState,
                lease = null,
                lastError = request.error,
            )
            ProviderOperationResult.Success(Unit)
        }
    }

    override suspend fun defer(
        request: QueueDeferralRequest,
    ): ProviderOperationResult<Unit> {
        recordedDeferralRequests += request
        return withValidatedLease(request.entryId, request.leaseId) { entry ->
            entries[request.entryId] = entry.copy(
                state = if (entry.retryAttempt == null) {
                    QueueEntryState.PENDING
                } else {
                    QueueEntryState.RETRY_WAITING
                },
                availableAt = request.availableAt,
                lease = null,
                lastError = null,
            )
            ProviderOperationResult.Success(Unit)
        }
    }

    override suspend fun fail(
        request: QueueFailureRequest,
    ): ProviderOperationResult<Unit> {
        recordedFailureRequests += request
        return withValidatedLease(request.entryId, request.leaseId) { entry ->
            entries[request.entryId] = entry.copy(
                state = when (request.disposition) {
                    QueueFailureDisposition.FAILED -> QueueEntryState.FAILED
                    QueueFailureDisposition.DEAD_LETTER -> QueueEntryState.DEAD_LETTER
                },
                lease = null,
                lastError = request.error,
            )
            ProviderOperationResult.Success(Unit)
        }
    }

    override suspend fun cancel(
        request: QueueCancellationRequest,
    ): ProviderOperationResult<Unit> {
        recordedCancellationRequests += request
        val entry = entries[request.entryId]
            ?: return ProviderOperationResult.Failure(
                InMemoryQueueError(message = "Queue entry ${request.entryId.value} was not found."),
            )
        if (entry.state == QueueEntryState.COMPLETED ||
            entry.state == QueueEntryState.FAILED ||
            entry.state == QueueEntryState.CANCELLED ||
            entry.state == QueueEntryState.DEAD_LETTER
        ) {
            return ProviderOperationResult.Failure(
                InMemoryQueueError(
                    message = "Queue entry ${request.entryId.value} cannot be cancelled from state ${entry.state}.",
                ),
            )
        }
        entries[request.entryId] = entry.copy(
            state = QueueEntryState.CANCELLED,
            lease = null,
            lastError = null,
        )
        return ProviderOperationResult.Success(Unit)
    }

    override suspend fun recoverExpiredLeases(
        request: ExpiredLeaseRecoveryRequest,
    ): ProviderOperationResult<ExpiredLeaseRecoveryResult> {
        recordedRecoveryRequests += request
        var recovered = 0
        entries.forEach { (entryId, entry) ->
            val lease = entry.lease
            if (entry.state == QueueEntryState.LEASED &&
                lease != null &&
                lease.expiresAt.epochMilliseconds < request.currentTime.epochMilliseconds
            ) {
                entries[entryId] = entry.copy(
                    state = if (entry.retryAttempt == null) {
                        QueueEntryState.PENDING
                    } else {
                        QueueEntryState.RETRY_WAITING
                    },
                    lease = null,
                    lastError = null,
                )
                recovered += 1
            }
        }
        return ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recoveredEntries = recovered))
    }

    /** Returns queue entry identifiers in durable insertion order. */
    public fun snapshotEntryIds(): List<QueueEntryId> = entries.keys.toList()

    /** Returns a snapshot of queue entry states keyed by entry identifier. */
    public fun snapshotStates(): Map<QueueEntryId, QueueEntryState> =
        entries.mapValues { (_, entry) -> entry.state }

    /** Clears request recordings and lifecycle recordings without deleting entries. */
    public fun clearRecordings() {
        recordedEnqueueRequests.clear()
        recordedAcquireRequests.clear()
        recordedCompletionRequests.clear()
        recordedRescheduleRequests.clear()
        recordedDeferralRequests.clear()
        recordedFailureRequests.clear()
        recordedCancellationRequests.clear()
        recordedRecoveryRequests.clear()
        lifecycleController.clearRecordings()
    }

    /** Clears all stored entries and recordings. */
    public fun resetState() {
        entries.clear()
        clearRecordings()
    }

    private fun QueueEntry.isEligibleAt(currentEpochMilliseconds: Long): Boolean {
        return (state == QueueEntryState.PENDING || state == QueueEntryState.RETRY_WAITING) &&
            availableAt.epochMilliseconds <= currentEpochMilliseconds
    }

    private fun withValidatedLease(
        entryId: QueueEntryId,
        leaseId: QueueLeaseId,
        onSuccess: (QueueEntry) -> ProviderOperationResult<Unit>,
    ): ProviderOperationResult<Unit> {
        val entry = entries[entryId]
            ?: return ProviderOperationResult.Failure(
                InMemoryQueueError(message = "Queue entry ${entryId.value} was not found."),
            )
        val lease = entry.lease
            ?: return ProviderOperationResult.Failure(
                InMemoryQueueError(message = "Queue entry ${entryId.value} does not have an active lease."),
            )
        if (entry.state != QueueEntryState.LEASED) {
            return ProviderOperationResult.Failure(
                InMemoryQueueError(
                    message = "Queue entry ${entryId.value} is in state ${entry.state} instead of LEASED.",
                ),
            )
        }
        if (lease.id != leaseId) {
            return ProviderOperationResult.Failure(
                InMemoryQueueError(
                    message = "Queue entry ${entryId.value} lease mismatch: expected ${lease.id.value}, got ${leaseId.value}.",
                ),
            )
        }
        return onSuccess(entry)
    }

    private data class IndexedEntry(
        val index: Int,
        val entry: QueueEntry,
    )

    private data class InMemoryQueueError(
        override val code: ErrorCode = ErrorCode("DL-TEST-QUEUE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}

private fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId("testing.queue.in-memory"),
    name = ProviderName("InMemoryQueueProvider"),
    type = ProviderType.QUEUE,
    version = ProviderVersion("1.0.0"),
    capabilities = setOf(
        ProviderCapability("testing"),
        ProviderCapability("in-memory"),
    ),
)
