package io.dataloom.queue.room

import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.QueueProvider
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.QueueEntryDao
import io.dataloom.queue.room.internal.QueueProviderError
import io.dataloom.queue.room.internal.toDomain
import io.dataloom.queue.room.internal.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AndroidX Room-backed implementation of [QueueProvider].
 *
 * ## Thread safety
 *
 * All operations are dispatched to [Dispatchers.IO] to avoid blocking the
 * calling coroutine context. No Room operation runs on the main thread.
 *
 * ## Atomic acquisition
 *
 * [acquire] uses a `@Transaction` DAO function that selects eligible entries
 * and updates them to LEASED state atomically within a single SQLite
 * transaction. Concurrent acquisition attempts cannot observe the same row
 * twice.
 *
 * ## Guarded transitions
 *
 * [complete], [reschedule], and [fail] include the lease identifier in the
 * SQL `WHERE` clause. A stale or mismatched lease yields zero affected rows,
 * which is mapped to a [io.dataloom.api.error.DataLoomError] with code
 * `QUEUE_STALE_LEASE`.
 *
 * ## Expired-lease recovery
 *
 * [recoverExpiredLeases] transitions LEASED entries with an expired lease
 * back to PENDING state in a single SQL UPDATE. The provider does not process
 * any entry — it only resets the lease columns. The exact recovered state is
 * always PENDING for this implementation.
 *
 * ## What this provider does NOT do
 *
 * - Does not execute synchronization.
 * - Does not decode or interpret payload or metadata content.
 * - Does not evaluate retry policy.
 * - Does not select dispatchers.
 * - Does not expose Room or SQLite types through the public API.
 * - Does not log credentials, tokens, keys, or complete user payloads.
 *
 * ## Cancellation
 *
 * `CancellationException` propagates normally through all operations.
 *
 * @param database the Room database instance. Must be held as a singleton by
 *   the caller; [RoomQueueProvider] does not manage the database lifecycle.
 */
public class RoomQueueProvider(
    private val database: DataLoomRoomDatabase,
) : QueueProvider {

    private val dao: QueueEntryDao by lazy { database.queueEntryDao() }

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.queue.room"),
        name = ProviderName("DataLoom Room Queue Provider"),
        type = ProviderType.QUEUE,
        version = ProviderVersion("1.0.0"),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    /**
     * Persists a new queue entry in the Room database.
     *
     * Returns [ProviderOperationResult.Failure] with `QUEUE_DUPLICATE_ENTRY`
     * when an entry with the same identifier already exists, or
     * `QUEUE_DATABASE_FAILURE` for other storage errors.
     */
    override suspend fun enqueue(
        request: QueueEnqueueRequest,
    ): ProviderOperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            dao.insert(request.entry.toEntity())
            ProviderOperationResult.Success(Unit)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            ProviderOperationResult.Failure(
                QueueProviderError.duplicateEntry(request.entry.id.value),
            )
        } catch (e: Exception) {
            ProviderOperationResult.Failure(QueueProviderError.databaseFailure())
        }
    }

    /**
     * Atomically acquires eligible queue entries and assigns an exclusive lease.
     *
     * Eligible entries are those in PENDING or RETRY_WAITING state where
     * `availableAt <= acquiredAt`, limited to [QueueAcquireRequest.maxEntries].
     *
     * Returns [QueueAcquireResult.NoEntries] when no eligible entries exist.
     * Returns [QueueAcquireResult.Entries] with all acquired entries and the
     * shared lease.
     */
    override suspend fun acquire(
        request: QueueAcquireRequest,
    ): ProviderOperationResult<QueueAcquireResult> = withContext(Dispatchers.IO) {
        try {
            val acquired = dao.acquireEntries(
                nowMs = request.acquiredAt.epochMilliseconds,
                leaseId = request.leaseId.value,
                consumerId = request.consumerId.value,
                acquiredAtMs = request.acquiredAt.epochMilliseconds,
                expiresAtMs = request.leaseExpiresAt.epochMilliseconds,
                limit = request.maxEntries,
            )
            if (acquired.isEmpty()) {
                ProviderOperationResult.Success(QueueAcquireResult.NoEntries)
            } else {
                val domainEntries = acquired.map { it.toDomain() }
                val lease = QueueLease(
                    id = request.leaseId,
                    consumerId = request.consumerId,
                    acquiredAt = request.acquiredAt,
                    expiresAt = request.leaseExpiresAt,
                )
                ProviderOperationResult.Success(
                    QueueAcquireResult.Entries(lease = lease, entries = domainEntries),
                )
            }
        } catch (e: Exception) {
            ProviderOperationResult.Failure(QueueProviderError.databaseFailure())
        }
    }

    /**
     * Marks a leased queue entry as successfully completed.
     *
     * Returns [ProviderOperationResult.Failure] with `QUEUE_STALE_LEASE` when
     * the supplied lease identifier does not match the current entry lease.
     */
    override suspend fun complete(
        request: QueueCompletionRequest,
    ): ProviderOperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val affected = dao.completeEntry(
                entryId = request.entryId.value,
                leaseId = request.leaseId.value,
            )
            if (affected == 0) {
                ProviderOperationResult.Failure(
                    QueueProviderError.staleLease(request.entryId.value),
                )
            } else {
                ProviderOperationResult.Success(Unit)
            }
        } catch (e: Exception) {
            ProviderOperationResult.Failure(QueueProviderError.databaseFailure())
        }
    }

    /**
     * Reschedules a leased queue entry for a future retry attempt.
     *
     * Returns [ProviderOperationResult.Failure] with `QUEUE_STALE_LEASE` when
     * the supplied lease identifier does not match the current entry lease.
     */
    override suspend fun reschedule(
        request: QueueRescheduleRequest,
    ): ProviderOperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val affected = dao.rescheduleEntry(
                entryId = request.entryId.value,
                leaseId = request.leaseId.value,
                availableAtMs = request.availableAt.epochMilliseconds,
                retryAttemptNumber = request.retryAttempt.number,
                errorCode = request.error.code.value,
                errorMessage = request.error.message,
            )
            if (affected == 0) {
                ProviderOperationResult.Failure(
                    QueueProviderError.staleLease(request.entryId.value),
                )
            } else {
                ProviderOperationResult.Success(Unit)
            }
        } catch (e: Exception) {
            ProviderOperationResult.Failure(QueueProviderError.databaseFailure())
        }
    }

    /**
     * Marks a leased queue entry as permanently failed or dead-lettered.
     *
     * The target state is determined by [QueueFailureRequest.disposition]:
     * - [QueueFailureDisposition.FAILED] → `FAILED`
     * - [QueueFailureDisposition.DEAD_LETTER] → `DEAD_LETTER`
     *
     * Returns [ProviderOperationResult.Failure] with `QUEUE_STALE_LEASE` when
     * the supplied lease identifier does not match the current entry lease.
     */
    override suspend fun fail(
        request: QueueFailureRequest,
    ): ProviderOperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetState = when (request.disposition) {
                QueueFailureDisposition.FAILED -> "FAILED"
                QueueFailureDisposition.DEAD_LETTER -> "DEAD_LETTER"
            }
            val affected = dao.failEntry(
                entryId = request.entryId.value,
                leaseId = request.leaseId.value,
                targetState = targetState,
                errorCode = request.error.code.value,
                errorMessage = request.error.message,
            )
            if (affected == 0) {
                ProviderOperationResult.Failure(
                    QueueProviderError.staleLease(request.entryId.value),
                )
            } else {
                ProviderOperationResult.Success(Unit)
            }
        } catch (e: Exception) {
            ProviderOperationResult.Failure(QueueProviderError.databaseFailure())
        }
    }

    /**
     * Cancels a queue entry that is in PENDING or RETRY_WAITING state.
     *
     * Cancellation of LEASED or terminal entries is refused. Returns
     * [ProviderOperationResult.Failure] with `QUEUE_CANCELLATION_REJECTED`
     * when the entry cannot be cancelled in its current state.
     */
    override suspend fun cancel(
        request: QueueCancellationRequest,
    ): ProviderOperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val affected = dao.cancelEntry(entryId = request.entryId.value)
            if (affected == 0) {
                ProviderOperationResult.Failure(
                    QueueProviderError.cancellationRejected(request.entryId.value),
                )
            } else {
                ProviderOperationResult.Success(Unit)
            }
        } catch (e: Exception) {
            ProviderOperationResult.Failure(QueueProviderError.databaseFailure())
        }
    }

    /**
     * Recovers queue entries whose exclusive leases have expired.
     *
     * Entries in LEASED state with `leaseExpiresAt < currentTime` are
     * transitioned back to PENDING state. All lease columns are cleared.
     *
     * Returns the number of recovered entries in [ExpiredLeaseRecoveryResult].
     */
    override suspend fun recoverExpiredLeases(
        request: ExpiredLeaseRecoveryRequest,
    ): ProviderOperationResult<ExpiredLeaseRecoveryResult> = withContext(Dispatchers.IO) {
        try {
            val recovered = dao.recoverExpiredLeases(
                nowMs = request.currentTime.epochMilliseconds,
            )
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recovered))
        } catch (e: Exception) {
            ProviderOperationResult.Failure(QueueProviderError.databaseFailure())
        }
    }
}
