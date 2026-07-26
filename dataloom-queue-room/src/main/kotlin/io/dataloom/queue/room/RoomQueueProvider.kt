package io.dataloom.queue.room

import android.database.sqlite.SQLiteConstraintException
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
import java.util.concurrent.CancellationException
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

    /** Persists one new queue entry. */
    override suspend fun enqueue(
        request: QueueEnqueueRequest,
    ): ProviderOperationResult<Unit> = executeDatabaseOperation {
        try {
            dao.insert(request.entry.toEntity())
            ProviderOperationResult.Success(Unit)
        } catch (_: SQLiteConstraintException) {
            ProviderOperationResult.Failure(
                QueueProviderError.duplicateEntry(request.entry.id.value),
            )
        }
    }

    /** Atomically acquires and leases at most [QueueAcquireRequest.maxEntries]. */
    override suspend fun acquire(
        request: QueueAcquireRequest,
    ): ProviderOperationResult<QueueAcquireResult> = executeDatabaseOperation {
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
            val lease = QueueLease(
                id = request.leaseId,
                consumerId = request.consumerId,
                acquiredAt = request.acquiredAt,
                expiresAt = request.leaseExpiresAt,
            )
            ProviderOperationResult.Success(
                QueueAcquireResult.Entries(
                    lease = lease,
                    entries = acquired.map { it.toDomain() },
                ),
            )
        }
    }

    /** Completes an entry only when the supplied lease is still current. */
    override suspend fun complete(
        request: QueueCompletionRequest,
    ): ProviderOperationResult<Unit> = executeDatabaseOperation {
        guardedTransitionResult(
            dao.completeEntry(
                entryId = request.entryId.value,
                leaseId = request.leaseId.value,
            ),
            request.entryId.value,
        )
    }

    /** Reschedules an entry only when the supplied lease is still current. */
    override suspend fun reschedule(
        request: QueueRescheduleRequest,
    ): ProviderOperationResult<Unit> = executeDatabaseOperation {
        guardedTransitionResult(
            dao.rescheduleEntry(
                entryId = request.entryId.value,
                leaseId = request.leaseId.value,
                availableAtMs = request.availableAt.epochMilliseconds,
                retryAttemptNumber = request.retryAttempt.number,
                errorCode = request.error.code.value,
                errorMessage = request.error.message,
            ),
            request.entryId.value,
        )
    }

    /** Permanently fails or dead-letters an entry guarded by its lease. */
    override suspend fun fail(
        request: QueueFailureRequest,
    ): ProviderOperationResult<Unit> = executeDatabaseOperation {
        val targetState = when (request.disposition) {
            QueueFailureDisposition.FAILED -> "FAILED"
            QueueFailureDisposition.DEAD_LETTER -> "DEAD_LETTER"
        }
        guardedTransitionResult(
            dao.failEntry(
                entryId = request.entryId.value,
                leaseId = request.leaseId.value,
                targetState = targetState,
                errorCode = request.error.code.value,
                errorMessage = request.error.message,
            ),
            request.entryId.value,
        )
    }

    /** Cancels an entry only while it is pending or waiting for retry. */
    override suspend fun cancel(
        request: QueueCancellationRequest,
    ): ProviderOperationResult<Unit> = executeDatabaseOperation {
        if (dao.cancelEntry(entryId = request.entryId.value) == 0) {
            ProviderOperationResult.Failure(
                QueueProviderError.cancellationRejected(request.entryId.value),
            )
        } else {
            ProviderOperationResult.Success(Unit)
        }
    }

    /** Recovers expired leases in one database update. */
    override suspend fun recoverExpiredLeases(
        request: ExpiredLeaseRecoveryRequest,
    ): ProviderOperationResult<ExpiredLeaseRecoveryResult> = executeDatabaseOperation {
        ProviderOperationResult.Success(
            ExpiredLeaseRecoveryResult(
                dao.recoverExpiredLeases(request.currentTime.epochMilliseconds),
            ),
        )
    }

    private fun guardedTransitionResult(
        affectedRows: Int,
        entryId: String,
    ): ProviderOperationResult<Unit> =
        if (affectedRows == 0) {
            ProviderOperationResult.Failure(QueueProviderError.staleLease(entryId))
        } else {
            ProviderOperationResult.Success(Unit)
        }

    private suspend fun <T> executeDatabaseOperation(
        operation: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> = withContext(Dispatchers.IO) {
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProviderOperationResult.Failure(QueueProviderError.databaseFailure())
        }
    }
}
