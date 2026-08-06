package io.dataloom.api.queue

import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.provider.ProviderOperationResult

/**
 * Result of atomically admitting one stable durable queue identity.
 *
 * An ordinary provider failure is reserved for storage, corruption, capacity,
 * cancellation, or another inability to determine the result. Duplicate
 * identity is represented explicitly and never inferred from an error message.
 */
public sealed interface QueueIdempotentAdmissionResult {
    /** Stable queue identity supplied by the caller. */
    public val queueEntryId: QueueEntryId

    /** Durable state observed when the admission decision completed. */
    public val currentState: QueueEntryState

    /** The provider created the durable entry during this call. */
    public data class Accepted(
        override val queueEntryId: QueueEntryId,
    ) : QueueIdempotentAdmissionResult {
        override val currentState: QueueEntryState = QueueEntryState.PENDING

        override fun toString(): String =
            "QueueIdempotentAdmissionResult.Accepted(currentState=$currentState)"
    }

    /**
     * The same immutable admission identity already exists.
     *
     * [currentState] may be pending, leased, waiting, completed, cancelled, or
     * terminal. The caller must not equate this result with currently runnable
     * work without inspecting the state.
     */
    public data class AlreadyAccepted(
        override val queueEntryId: QueueEntryId,
        override val currentState: QueueEntryState,
    ) : QueueIdempotentAdmissionResult {
        override fun toString(): String =
            "QueueIdempotentAdmissionResult.AlreadyAccepted(currentState=$currentState)"
    }

    /**
     * The stable queue ID exists for different immutable work.
     *
     * Providers must not replace, merge, reset, or expose the existing entry.
     */
    public data class IdentityConflict(
        override val queueEntryId: QueueEntryId,
        override val currentState: QueueEntryState,
    ) : QueueIdempotentAdmissionResult {
        override fun toString(): String =
            "QueueIdempotentAdmissionResult.IdentityConflict(currentState=$currentState)"
    }
}

/**
 * Additive queue capability for atomic first-or-existing admission.
 *
 * Implementations must make the following decision under the same transaction,
 * file lock, or equivalent provider-owned atomic boundary:
 *
 * 1. create the entry when its ID is absent and return [QueueIdempotentAdmissionResult.Accepted];
 * 2. return [QueueIdempotentAdmissionResult.AlreadyAccepted] when the existing
 *    entry has the same immutable admission identity; or
 * 3. return [QueueIdempotentAdmissionResult.IdentityConflict] when the same ID
 *    belongs to different immutable work.
 *
 * The operation must invoke no scheduler, worker, retry policy, transport,
 * application storage, conflict engine, or observer. Cancellation propagates.
 */
public interface QueueIdempotentAdmissionProvider : QueueProvider {
    public suspend fun admit(
        request: QueueEnqueueRequest,
    ): ProviderOperationResult<QueueIdempotentAdmissionResult>
}

/**
 * Compares only the immutable identity of two queue admissions.
 *
 * Included identity:
 *
 * - stable queue entry ID;
 * - exact synchronization request and execution context;
 * - queue-entry metadata;
 * - immutable workflow timeout evidence;
 * - persisted strategy decision; and
 * - complete immutable accepted strategy plan.
 *
 * Deliberately excluded mutable execution state:
 *
 * - lifecycle state;
 * - enqueue and availability timestamps;
 * - retry attempt and budget;
 * - active lease;
 * - last error.
 *
 * Excluding mutable state allows an ambiguous first admission to be reconciled
 * after leasing, retry, completion, cancellation, process death, or a caller
 * retry that supplies a later timestamp. Different logical work under the same
 * ID still fails closed.
 */
public fun QueueEntry.hasSameQueueAdmissionIdentityAs(
    other: QueueEntry,
): Boolean =
    id == other.id &&
        synchronizationRequest == other.synchronizationRequest &&
        metadata == other.metadata &&
        workflowTimeoutState == other.workflowTimeoutState &&
        strategyDecision == other.strategyDecision &&
        strategyPlan == other.strategyPlan
