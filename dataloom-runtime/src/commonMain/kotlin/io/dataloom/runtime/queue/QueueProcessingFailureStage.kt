package io.dataloom.runtime.queue

/**
 * Identifies the stage at which a [io.dataloom.api.queue.QueueProvider]
 * operation failed during a [DurableQueueExecutionProcessor] processing cycle.
 *
 * [QueueProcessingFailureStage] is carried by
 * [QueueProcessingResult.QueueProviderFailure] to allow callers to distinguish
 * acquisition failures from individual transition failures.
 *
 * Enum ordinals are not a compatibility contract and must not be persisted.
 * Use the name of the enum constant for persistence and serialization.
 */
public enum class QueueProcessingFailureStage {

    /**
     * The [io.dataloom.api.queue.QueueProvider.acquire] call failed with
     * a [io.dataloom.api.provider.ProviderOperationResult.Failure].
     *
     * No handler was invoked.
     */
    ACQUISITION,

    /**
     * The [io.dataloom.api.queue.QueueProvider.acquire] call returned a
     * structurally invalid result that violates the acquisition contract
     * (for example: duplicate entry identifiers or consumer-identity
     * mismatch).
     *
     * No handler was invoked. Reported as
     * [QueueProcessingResult.QueueContractViolation] rather than this stage.
     * This value is reserved for future validation distinctions.
     */
    ACQUISITION_VALIDATION,

    /**
     * The [io.dataloom.api.queue.QueueProvider.complete] call failed for
     * an entry whose handler returned [QueueEntryExecutionOutcome.Completed].
     */
    COMPLETION_TRANSITION,

    /**
     * The [io.dataloom.api.queue.QueueProvider.reschedule] call failed
     * for an entry whose handler returned [QueueEntryExecutionOutcome.Reschedule].
     */
    RESCHEDULE_TRANSITION,

    /**
     * The [io.dataloom.api.queue.QueueProvider.fail] call failed for an
     * entry whose handler returned [QueueEntryExecutionOutcome.Failed].
     */
    FAILURE_TRANSITION,

    /**
     * The [io.dataloom.api.queue.QueueProvider.cancel] call failed for an
     * entry whose handler returned [QueueEntryExecutionOutcome.Cancelled].
     */
    CANCELLATION_TRANSITION,
}
