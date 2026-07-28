package io.dataloom.api.queue

import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType

/**
 * Platform-independent provider contract for DataLoom durable queue
 * persistence.
 *
 * [QueueProvider] is the persistence boundary between the DataLoom runtime and
 * a concrete durable storage implementation such as Room or SQLDelight.
 *
 * ```text
 * DataLoom Runtime
 *       ↓
 * QueueProvider
 *       ↓
 * Room / SQLDelight / custom durable storage
 * ```
 *
 * ## Responsibilities
 *
 * [QueueProvider] persists, acquires, completes, reschedules, fails, cancels,
 * and recovers DataLoom queue execution records. It is infrastructure storage
 * for the DataLoom runtime itself.
 *
 * [QueueProvider] is distinct from
 * [io.dataloom.api.storage.StorageProvider], which adapts application-controlled
 * domain storage to synchronization operations.
 *
 * ```text
 * StorageProvider → reads and applies application synchronization changes
 * QueueProvider   → persists DataLoom workflow execution records
 * ```
 *
 * ## Atomic acquisition invariant
 *
 * Acquiring eligible queue entries and assigning their lease must be one atomic
 * provider operation. A provider must not:
 *
 * 1. Read eligible entries.
 * 2. Return them.
 * 3. Assign the lease later in a separate operation.
 *
 * That pattern could allow multiple consumers to process the same entry
 * concurrently.
 *
 * ## Lease-protected updates
 *
 * Operations that modify a leased entry ([complete], [reschedule], [fail])
 * must verify that the supplied lease identifier matches the currently active
 * entry lease. A stale or mismatched lease must result in a canonical
 * [io.dataloom.api.error.DataLoomError] failure.
 *
 * ## Application storage boundary
 *
 * - Application UI must not query [QueueProvider] for business data.
 * - DataLoom queue records must not become application domain entities.
 * - A Room implementation may physically share the same database, but schemas
 *   and ownership boundaries must remain separate.
 * - Queue payloads and metadata may be sensitive and require secure storage
 *   according to application policy.
 *
 * ## What this provider must not do
 *
 * Implementations must not:
 * - Expose Room, SQLite, SQLDelight, DataStore, file, cursor, transaction, or
 *   other platform-specific types through the public API.
 * - Select threads or dispatchers.
 * - Expose coroutine scopes.
 * - Execute synchronization.
 * - Call transport or application storage providers.
 * - Evaluate retry policy.
 * - Resolve conflicts.
 * - Automatically log payloads or sensitive metadata.
 *
 * ## Thread safety
 *
 * Implementations are responsible for documenting and enforcing their own
 * thread-safety guarantees and transactional integrity.
 *
 * ## Cancellation
 *
 * Implementations must preserve coroutine cancellation and must not convert
 * cancellation exceptions into normal failures.
 *
 * ## Descriptor
 *
 * Implementations must expose a [descriptor] whose
 * [ProviderDescriptor.type] is [ProviderType.QUEUE].
 */
public interface QueueProvider : DataLoomProvider {

    /**
     * Immutable descriptor for this queue provider.
     *
     * [ProviderDescriptor.type] must be [ProviderType.QUEUE].
     */
    override public val descriptor: ProviderDescriptor

    /**
     * Persists a new queue entry in durable storage.
     *
     * Construction of the request does not persist the entry. This operation
     * is the first step in the queue entry lifecycle and stores an entry in
     * [io.dataloom.api.queue.QueueEntryState.PENDING] state.
     *
     * A provider must return a canonical error rather than silently replacing
     * an existing entry with the same identifier unless future policy
     * explicitly permits replacement.
     *
     * The implementation must preserve coroutine cancellation.
     *
     * @param request immutable request containing the entry to enqueue.
     * @return [ProviderOperationResult.Success] when the entry was persisted
     *   successfully, or [ProviderOperationResult.Failure] with a canonical
     *   DataLoom error.
     */
    public suspend fun enqueue(
        request: QueueEnqueueRequest,
    ): ProviderOperationResult<Unit>

    /**
     * Atomically acquires eligible queue entries and assigns them an exclusive
     * lease.
     *
     * Eligible entries are those in [io.dataloom.api.queue.QueueEntryState.PENDING]
     * or [io.dataloom.api.queue.QueueEntryState.RETRY_WAITING] state where
     * [io.dataloom.api.queue.QueueEntry.availableAt] is not later than
     * [io.dataloom.api.queue.QueueAcquireRequest.acquiredAt].
     *
     * **Atomic acquisition is mandatory.** The provider must not read eligible
     * entries, return them, and assign the lease in a separate step. That
     * pattern could allow multiple consumers to process the same entry
     * concurrently.
     *
     * Priority, fairness, and scheduling policy are owned by the DataLoom
     * runtime, not the provider.
     *
     * The implementation must preserve coroutine cancellation.
     *
     * @param request immutable request describing the consumer, lease, and
     *   acquisition constraints.
     * @return [ProviderOperationResult.Success] with a [QueueAcquireResult.NoEntries]
     *   when no eligible entries are available, or [ProviderOperationResult.Success]
     *   with a [QueueAcquireResult.Entries] containing at least one acquired entry,
     *   or [ProviderOperationResult.Failure] with a canonical DataLoom error.
     */
    public suspend fun acquire(
        request: QueueAcquireRequest,
    ): ProviderOperationResult<QueueAcquireResult>

    /**
     * Marks a leased queue entry as successfully completed.
     *
     * The provider must verify that the supplied lease identifier matches the
     * currently active entry lease. Completion using a stale or mismatched
     * lease must fail canonically.
     *
     * A successful operation transitions the entry to
     * [io.dataloom.api.queue.QueueEntryState.COMPLETED] and clears the active
     * lease.
     *
     * The implementation must preserve coroutine cancellation.
     *
     * @param request immutable request identifying the entry and validating the
     *   active lease.
     * @return [ProviderOperationResult.Success] when the entry was completed
     *   successfully, or [ProviderOperationResult.Failure] with a canonical
     *   DataLoom error if the lease is stale, mismatched, or if the update
     *   fails.
     */
    public suspend fun complete(
        request: QueueCompletionRequest,
    ): ProviderOperationResult<Unit>

    /**
     * Reschedules a leased queue entry for a future retry attempt.
     *
     * The provider must verify that the supplied lease identifier matches the
     * currently active entry lease.
     *
     * A successful operation:
     * - Transitions the entry to [io.dataloom.api.queue.QueueEntryState.RETRY_WAITING].
     * - Clears the active lease.
     * - Stores the retry attempt, availability instant, and canonical error.
     *
     * The provider must not evaluate retry policy. The DataLoom runtime
     * supplies the retry attempt and availability instant after evaluating
     * retry policy externally.
     *
     * The implementation must preserve coroutine cancellation.
     *
     * @param request immutable request containing the retry attempt, next
     *   availability instant, and the error that caused the failure.
     * @return [ProviderOperationResult.Success] when the entry was rescheduled
     *   successfully, or [ProviderOperationResult.Failure] with a canonical
     *   DataLoom error.
     */
    public suspend fun reschedule(
        request: QueueRescheduleRequest,
    ): ProviderOperationResult<Unit>

    /**
     * Marks a leased queue entry as permanently failed or dead-lettered.
     *
     * The provider must verify that the supplied lease identifier matches the
     * currently active entry lease.
     *
     * A successful operation:
     * - Transitions the entry to [io.dataloom.api.queue.QueueEntryState.FAILED]
     *   or [io.dataloom.api.queue.QueueEntryState.DEAD_LETTER] based on the
     *   supplied [io.dataloom.api.queue.QueueFailureDisposition].
     * - Clears the active lease.
     * - Stores the canonical error.
     *
     * The provider must not evaluate retry policy or recoverability.
     *
     * The implementation must preserve coroutine cancellation.
     *
     * @param request immutable request containing the canonical error and the
     *   failure disposition.
     * @return [ProviderOperationResult.Success] when the entry was failed
     *   successfully, or [ProviderOperationResult.Failure] with a canonical
     *   DataLoom error.
     */
    public suspend fun fail(
        request: QueueFailureRequest,
    ): ProviderOperationResult<Unit>

    /**
     * Requests cancellation of a queue entry.
     *
     * Cancellation of an actively leased entry may fail or be deferred
     * according to provider and runtime policy.
     *
     * Cancellation does not automatically cancel a running coroutine. Runtime
     * execution cancellation is deferred to a future issue.
     *
     * A successful cancellation transitions the entry to
     * [io.dataloom.api.queue.QueueEntryState.CANCELLED].
     *
     * The implementation must preserve coroutine cancellation.
     *
     * @param request immutable request identifying the entry to cancel and
     *   carrying the execution context.
     * @return [ProviderOperationResult.Success] when cancellation was processed,
     *   or [ProviderOperationResult.Failure] with a canonical DataLoom error.
     */
    public suspend fun cancel(
        request: QueueCancellationRequest,
    ): ProviderOperationResult<Unit>

    /**
     * Recovers queue entries whose exclusive leases have expired.
     *
     * This operation is intended to handle process-death scenarios where a
     * consumer acquired an entry but terminated before completing, rescheduling,
     * or failing it. Expired entries must not remain permanently stuck in
     * [io.dataloom.api.queue.QueueEntryState.LEASED] state.
     *
     * ## Process-death recovery flow
     *
     * ```text
     * Entry is LEASED
     *       ↓
     * Process terminates
     *       ↓
     * Lease expires
     *       ↓
     * recoverExpiredLeases(...)
     *       ↓
     * Entry becomes recoverable
     * ```
     *
     * Recovery requirements:
     * - Must be based on persisted lease information.
     * - Must not assume in-memory state survived process termination.
     * - Must not process an unexpired lease.
     * - Implementations must document the recovered state transition
     *   ([io.dataloom.api.queue.QueueEntryState.PENDING] or
     *   [io.dataloom.api.queue.QueueEntryState.RETRY_WAITING]) and their
     *   transactional guarantees.
     *
     * The implementation must preserve coroutine cancellation.
     *
     * @param request immutable request carrying the current time used to
     *   identify expired leases.
     * @return [ProviderOperationResult.Success] with an
     *   [ExpiredLeaseRecoveryResult] reporting how many entries were recovered,
     *   or [ProviderOperationResult.Failure] with a canonical DataLoom error.
     */
    public suspend fun recoverExpiredLeases(
        request: ExpiredLeaseRecoveryRequest,
    ): ProviderOperationResult<ExpiredLeaseRecoveryResult>
}
