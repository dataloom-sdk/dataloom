package io.dataloom.runtime.queue

import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.flock

/**
 * Production Apple file-backed [QueueProvider].
 *
 * The caller supplies an absolute application-private directory, normally a
 * dedicated child of Application Support. Construction performs no I/O. The
 * directory, lock file, and queue snapshot are created lazily by the first
 * operation.
 *
 * Every queue operation takes one process-shared advisory lock and operates on
 * one bounded complete snapshot. Acquisition therefore selects eligible work
 * and writes the shared lease atomically. Leased transitions validate the exact
 * current lease before changing durable state.
 *
 * Successful mutations write an owner-only temporary file, fsync it, atomically
 * rename it over the previous snapshot, and fsync the parent directory before
 * returning success. The complete snapshot is capped at 32 MiB, 10,000 queue
 * entries, and 10,000 administrative retry receipts.
 *
 * Version-1 entry-only, version-2 entry-plus-receipt, and version-3
 * strategy-decision snapshots remain readable. Every successful mutation writes
 * version 4 with the complete immutable accepted strategy plan and preserves
 * existing receipts, bounded strategy identity, and legacy identity-only work.
 *
 * The provider persists synchronization identifiers, safe metadata, retry
 * history, retry budgets, immutable workflow timeout evidence, bounded strategy
 * decision identity, lease state, and sanitized canonical errors. It must not
 * be used for credentials, tokens,
 * encryption keys, raw exception text, or arbitrary payload bodies.
 */
public class AppleFileQueueProvider(
    directoryPath: String,
    fileName: String = DEFAULT_FILE_NAME,
) : QueueProvider {
    private val normalizedDirectoryPath: String = appleQueueValidateDirectoryPath(directoryPath)
    private val validatedFileName: String = appleQueueValidateFileName(fileName)
    private val dataFilePath: String = "$normalizedDirectoryPath/$validatedFileName"
    private val lockFilePath: String = "$dataFilePath.lock"
    private val temporaryFilePath: String = "$dataFilePath.tmp"

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.queue.apple.file"),
        name = ProviderName("DataLoom Apple File Queue Provider"),
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

    override suspend fun enqueue(
        request: QueueEnqueueRequest,
    ): ProviderOperationResult<Unit> = appleQueueExecute {
        appleQueueWithExclusiveLock {
            val snapshot = appleQueueReadSnapshot()
            val entries = snapshot.entries
            val entryId = request.entry.id.value
            if (entries.containsKey(entryId)) {
                return@appleQueueWithExclusiveLock ProviderOperationResult.Failure(
                    AppleQueueProviderError.duplicateEntry(entryId),
                )
            }
            if (entries.size >= APPLE_QUEUE_MAX_ENTRY_COUNT) {
                throw AppleQueueEntryLimitException()
            }
            entries[entryId] = request.entry.copy(lastError = null)
            currentCoroutineContext().ensureActive()
            appleQueueWriteSnapshot(snapshot)
            ProviderOperationResult.Success(Unit)
        }
    }

    override suspend fun acquire(
        request: QueueAcquireRequest,
    ): ProviderOperationResult<QueueAcquireResult> = appleQueueExecute {
        appleQueueWithExclusiveLock {
            val snapshot = appleQueueReadSnapshot()
            val entries = snapshot.entries
            val eligible = entries.values
                .asSequence()
                .filter { entry ->
                    (entry.state == QueueEntryState.PENDING ||
                        entry.state == QueueEntryState.RETRY_WAITING) &&
                        entry.availableAt.epochMilliseconds <= request.acquiredAt.epochMilliseconds
                }
                .sortedWith(
                    compareBy<QueueEntry>(
                        { it.availableAt.epochMilliseconds },
                        { it.enqueuedAt.epochMilliseconds },
                        { it.id.value },
                    ),
                )
                .take(request.maxEntries)
                .toList()

            if (eligible.isEmpty()) {
                return@appleQueueWithExclusiveLock ProviderOperationResult.Success(
                    QueueAcquireResult.NoEntries,
                )
            }

            val lease = QueueLease(
                id = request.leaseId,
                consumerId = request.consumerId,
                acquiredAt = request.acquiredAt,
                expiresAt = request.leaseExpiresAt,
            )
            val acquired = eligible.map { entry ->
                entry.copy(
                    state = QueueEntryState.LEASED,
                    lease = lease,
                    lastError = null,
                ).also { leased -> entries[leased.id.value] = leased }
            }
            currentCoroutineContext().ensureActive()
            appleQueueWriteSnapshot(snapshot)
            ProviderOperationResult.Success(
                QueueAcquireResult.Entries(
                    lease = lease,
                    entries = acquired,
                ),
            )
        }
    }

    override suspend fun complete(
        request: QueueCompletionRequest,
    ): ProviderOperationResult<Unit> = appleQueueGuardedTransition(
        entryId = request.entryId,
        leaseId = request.leaseId,
    ) { entry ->
        entry.copy(
            state = QueueEntryState.COMPLETED,
            lease = null,
            lastError = null,
        )
    }

    override suspend fun reschedule(
        request: QueueRescheduleRequest,
    ): ProviderOperationResult<Unit> = appleQueueGuardedTransition(
        entryId = request.entryId,
        leaseId = request.leaseId,
    ) { entry ->
        entry.copy(
            state = QueueEntryState.RETRY_WAITING,
            availableAt = request.availableAt,
            retryAttempt = request.retryAttempt,
            retryBudgetState = request.retryBudgetState,
            lease = null,
            lastError = request.error.appleQueueSanitizedCopy(),
        )
    }

    override suspend fun defer(
        request: QueueDeferralRequest,
    ): ProviderOperationResult<Unit> = appleQueueGuardedTransition(
        entryId = request.entryId,
        leaseId = request.leaseId,
    ) { entry ->
        entry.copy(
            state = if (entry.retryAttempt == null) {
                QueueEntryState.PENDING
            } else {
                QueueEntryState.RETRY_WAITING
            },
            availableAt = request.availableAt,
            lease = null,
            lastError = null,
        )
    }

    override suspend fun fail(
        request: QueueFailureRequest,
    ): ProviderOperationResult<Unit> = appleQueueGuardedTransition(
        entryId = request.entryId,
        leaseId = request.leaseId,
    ) { entry ->
        entry.copy(
            state = when (request.disposition) {
                QueueFailureDisposition.FAILED -> QueueEntryState.FAILED
                QueueFailureDisposition.DEAD_LETTER -> QueueEntryState.DEAD_LETTER
            },
            lease = null,
            lastError = request.error.appleQueueSanitizedCopy(),
        )
    }

    override suspend fun cancel(
        request: QueueCancellationRequest,
    ): ProviderOperationResult<Unit> = appleQueueExecute {
        appleQueueWithExclusiveLock {
            val snapshot = appleQueueReadSnapshot()
            val entries = snapshot.entries
            val entry = entries[request.entryId.value]
            if (entry == null ||
                (entry.state != QueueEntryState.PENDING &&
                    entry.state != QueueEntryState.RETRY_WAITING)
            ) {
                return@appleQueueWithExclusiveLock ProviderOperationResult.Failure(
                    AppleQueueProviderError.cancellationRejected(request.entryId.value),
                )
            }
            entries[entry.id.value] = entry.copy(
                state = QueueEntryState.CANCELLED,
                lastError = null,
            )
            currentCoroutineContext().ensureActive()
            appleQueueWriteSnapshot(snapshot)
            ProviderOperationResult.Success(Unit)
        }
    }

    override suspend fun recoverExpiredLeases(
        request: ExpiredLeaseRecoveryRequest,
    ): ProviderOperationResult<ExpiredLeaseRecoveryResult> = appleQueueExecute {
        appleQueueWithExclusiveLock {
            val snapshot = appleQueueReadSnapshot()
            val entries = snapshot.entries
            var recovered = 0
            entries.values.toList().forEach { entry ->
                val lease = entry.lease
                if (entry.state == QueueEntryState.LEASED &&
                    lease != null &&
                    lease.expiresAt.epochMilliseconds < request.currentTime.epochMilliseconds
                ) {
                    entries[entry.id.value] = entry.copy(
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
            if (recovered > 0) {
                currentCoroutineContext().ensureActive()
                appleQueueWriteSnapshot(snapshot)
            }
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recovered))
        }
    }

    private suspend fun appleQueueGuardedTransition(
        entryId: QueueEntryId,
        leaseId: QueueLeaseId,
        transition: (QueueEntry) -> QueueEntry,
    ): ProviderOperationResult<Unit> = appleQueueExecute {
        appleQueueWithExclusiveLock {
            val snapshot = appleQueueReadSnapshot()
            val entries = snapshot.entries
            val current = entries[entryId.value]
            if (current == null ||
                current.state != QueueEntryState.LEASED ||
                current.lease?.id != leaseId
            ) {
                return@appleQueueWithExclusiveLock ProviderOperationResult.Failure(
                    AppleQueueProviderError.staleLease(entryId.value),
                )
            }
            entries[entryId.value] = transition(current)
            currentCoroutineContext().ensureActive()
            appleQueueWriteSnapshot(snapshot)
            ProviderOperationResult.Success(Unit)
        }
    }

    private suspend fun <T> appleQueueExecute(
        block: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> = try {
        currentCoroutineContext().ensureActive()
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AppleQueueFileLimitException) {
        ProviderOperationResult.Failure(AppleQueueProviderError.stateLimitExceeded())
    } catch (_: AppleQueueEntryLimitException) {
        ProviderOperationResult.Failure(AppleQueueProviderError.entryLimitExceeded())
    } catch (_: AppleQueueReceiptLimitException) {
        ProviderOperationResult.Failure(AppleQueueProviderError.stateLimitExceeded())
    } catch (_: AppleQueueMalformedStateException) {
        ProviderOperationResult.Failure(AppleQueueProviderError.integrityFailure())
    } catch (_: AppleQueueFileException) {
        ProviderOperationResult.Failure(AppleQueueProviderError.fileFailure())
    } catch (_: Exception) {
        ProviderOperationResult.Failure(AppleQueueProviderError.fileFailure())
    }

    private suspend fun <T> appleQueueWithExclusiveLock(
        block: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> {
        appleQueueEnsurePrivateDirectory(normalizedDirectoryPath)
        val descriptor = appleQueueOpenOwnerOnly(lockFilePath, O_RDWR or O_CREAT)
        try {
            appleQueueAcquireExclusiveLock(descriptor)
            return try {
                currentCoroutineContext().ensureActive()
                block()
            } finally {
                flock(descriptor, LOCK_UN)
            }
        } finally {
            close(descriptor)
        }
    }

    private suspend fun appleQueueAcquireExclusiveLock(descriptor: Int) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (flock(descriptor, LOCK_EX or LOCK_NB) == 0) return
            when (errno) {
                EINTR -> Unit
                EAGAIN, EWOULDBLOCK -> delay(APPLE_QUEUE_LOCK_RETRY_DELAY_MILLISECONDS)
                else -> throw AppleQueueFileException()
            }
        }
    }

    private fun appleQueueReadSnapshot(): AppleQueueSnapshot {
        val content = appleQueueReadUtf8FileOrNull(dataFilePath) ?: return AppleQueueSnapshot()
        return AppleQueueStateFileCodec.decodeSnapshot(content)
    }

    private fun appleQueueWriteSnapshot(snapshot: AppleQueueSnapshot) {
        val content = AppleQueueStateFileCodec.encodeSnapshot(snapshot)
        appleQueueWriteUtf8FileAtomically(
            temporaryPath = temporaryFilePath,
            destinationPath = dataFilePath,
            content = content,
        )
    }

    public companion object {
        /** Historical file name retained so version-1 snapshots migrate in place. */
        public const val DEFAULT_FILE_NAME: String = "dataloom-queue-state-v1.tsv"
    }
}
