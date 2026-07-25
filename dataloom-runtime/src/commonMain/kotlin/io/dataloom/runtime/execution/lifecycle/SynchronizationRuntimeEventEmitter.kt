package io.dataloom.runtime.execution.lifecycle

import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationProgress
import io.dataloom.runtime.observation.SynchronizationEventDispatchResult

/**
 * Extended event-emitter contract that adds operational event emission to the
 * synchronization lifecycle emitter.
 *
 * ## Purpose
 *
 * [SynchronizationRuntimeEventEmitter] extends
 * [SynchronizationLifecycleEventEmitter] with three operational event-emission
 * capabilities required by DL-030:
 *
 * - [emitProgressUpdated]: emitted after durable batch-level progress is
 *   confirmed.
 * - [emitRetryScheduled]: emitted after the scheduler accepts a retry request.
 * - [emitConflictDetected]: emitted after a real conflict is detected and
 *   before the resolver is invoked.
 *
 * ## Inheritance
 *
 * [SynchronizationRuntimeEventEmitter] extends
 * [SynchronizationLifecycleEventEmitter], so every lifecycle event method
 * ([emitStarted], [emitPhaseChanged], [emitCompleted]) is also available.
 * A single implementation satisfies both contracts without duplicating
 * dispatch logic.
 *
 * ## Event-ID and timestamp generation
 *
 * Every method generates one fresh
 * [io.dataloom.api.identifier.SynchronizationEventId] and reads the injected
 * [io.dataloom.api.time.DataLoomClock] exactly once per emitted event. Neither
 * generation nor clock-read occurs when the emitter is absent.
 *
 * ## Observer failure isolation
 *
 * Ordinary observer callback failures are isolated by the dispatcher and
 * returned as structured [SynchronizationEventDispatchResult] values. These
 * results must not change synchronization results, retry decisions, or
 * conflict resolution decisions.
 *
 * ## Cancellation behavior
 *
 * A [kotlin.coroutines.cancellation.CancellationException] thrown during any
 * dispatch propagates normally. For operational events this has the following
 * consequences:
 *
 * - [emitProgressUpdated]: the batch was already durably completed before
 *   emission; durable work is not rolled back.
 * - [emitRetryScheduled]: the scheduler has already accepted the schedule;
 *   the schedule is not automatically cancelled.
 * - [emitConflictDetected]: resolver lookup and resolution must not continue.
 *
 * ## No platform API
 *
 * Does not use System.currentTimeMillis(), java.time, kotlin.random,
 * UUID.randomUUID(), Android clocks, or any platform-specific API. All
 * timestamps and identifiers are supplied through injected abstractions.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and runtime types only.
 * Safe for use in Kotlin Multiplatform common code.
 */
public interface SynchronizationRuntimeEventEmitter : SynchronizationLifecycleEventEmitter {

    /**
     * Emits a [io.dataloom.api.synchronization.SynchronizationEvent.ProgressUpdated]
     * event representing durable batch-level progress.
     *
     * Must be called only after durable batch-level work is confirmed:
     *
     * - Outbound: only after a [ChangeSetAcknowledgement] is durably persisted.
     * - Inbound: only after inbound changes are applied and the required next
     *   checkpoint (when present) is durably persisted.
     *
     * ## Progress semantics
     *
     * [progress] must represent cumulative, non-decreasing work completed
     * within the current pipeline execution. Values must satisfy all DL-016
     * [io.dataloom.api.synchronization.SynchronizationProgress] invariants:
     * `completed >= 0`, `total >= 0` when provided, `completed <= total`.
     *
     * ## Event-ID and timestamp
     *
     * Generates one fresh [io.dataloom.api.identifier.SynchronizationEventId]
     * and reads the clock exactly once for this event.
     *
     * ## Cancellation
     *
     * A thrown [kotlin.coroutines.cancellation.CancellationException]
     * propagates normally. The durable batch work has already been completed
     * at this point; cancellation during progress delivery does not undo the
     * completed provider work.
     *
     * @param request the exact [SynchronizationRequest] for the active
     *   synchronization execution. Preserved unchanged.
     * @param progress the current cumulative progress snapshot for this
     *   execution. Must satisfy all DL-016 invariants.
     * @return the [SynchronizationEventDispatchResult] returned by the
     *   dispatcher. Structured observer failures do not prevent subsequent
     *   batch processing.
     */
    public suspend fun emitProgressUpdated(
        request: SynchronizationRequest,
        progress: SynchronizationProgress,
    ): SynchronizationEventDispatchResult

    /**
     * Emits a [io.dataloom.api.synchronization.SynchronizationEvent.RetryScheduled]
     * event after the scheduler has accepted a retry request.
     *
     * Must be called only after [io.dataloom.api.scheduling.SchedulerProvider]
     * returns a successful [io.dataloom.api.scheduling.ScheduleReceipt]. Must
     * not be called when the scheduler is absent, returns a failure, or throws.
     *
     * ## Event-ID and timestamp
     *
     * Generates one fresh [io.dataloom.api.identifier.SynchronizationEventId]
     * and reads the clock exactly once for this event.
     *
     * ## Cancellation
     *
     * A thrown [kotlin.coroutines.cancellation.CancellationException]
     * propagates normally. The scheduler has already accepted the schedule at
     * this point; cancellation during event delivery does not cancel the
     * accepted schedule.
     *
     * @param request the exact [SynchronizationRequest] being retried.
     *   Preserved unchanged.
     * @param attempt the [RetryAttempt] descriptor for this scheduled retry.
     *   Preserved unchanged.
     * @param delay the selected minimum scheduling delay. Preserved unchanged.
     * @param error the canonical [DataLoomError] that triggered the retry.
     *   Preserved unchanged. Must not expose payload bytes, credentials, or
     *   personal data.
     * @return the [SynchronizationEventDispatchResult] returned by the
     *   dispatcher. Structured observer failures do not change the
     *   [io.dataloom.runtime.retry.RetryOrchestrationResult].
     */
    public suspend fun emitRetryScheduled(
        request: SynchronizationRequest,
        attempt: RetryAttempt,
        delay: SchedulingDelay,
        error: DataLoomError,
    ): SynchronizationEventDispatchResult

    /**
     * Emits a [io.dataloom.api.synchronization.SynchronizationEvent.ConflictDetected]
     * event after a real conflict is detected and before resolver invocation.
     *
     * Must be called only after
     * [io.dataloom.api.conflict.ConflictDetector.detect] reports an actual
     * [io.dataloom.api.conflict.ConflictDetectionResult.ConflictDetected]
     * result. Must not be called when no conflict is detected, when the
     * detector is absent, or when the detector throws.
     *
     * ## Ordering
     *
     * Emission must occur before resolver lookup and before
     * [io.dataloom.api.conflict.ConflictResolver.resolve] is invoked.
     *
     * ## Event-ID and timestamp
     *
     * Generates one fresh [io.dataloom.api.identifier.SynchronizationEventId]
     * and reads the clock exactly once for this event.
     *
     * ## Cancellation
     *
     * A thrown [kotlin.coroutines.cancellation.CancellationException]
     * propagates normally. When cancelled here, resolver lookup and resolution
     * must not continue. The detected conflict remains unmodified.
     *
     * ## Security
     *
     * Does not log, expose, or copy the payload bytes of [conflict]. The
     * [io.dataloom.api.conflict.SynchronizationConflict] reference is
     * preserved exactly as received from the detector.
     *
     * @param request the exact [SynchronizationRequest] in which the conflict
     *   was detected. Preserved unchanged.
     * @param conflict the exact [SynchronizationConflict] reported by the
     *   detector. Not mutated. Payload bytes are not copied or logged.
     * @return the [SynchronizationEventDispatchResult] returned by the
     *   dispatcher. Structured observer failures do not stop resolver selection
     *   or resolution.
     */
    public suspend fun emitConflictDetected(
        request: SynchronizationRequest,
        conflict: SynchronizationConflict,
    ): SynchronizationEventDispatchResult
}
