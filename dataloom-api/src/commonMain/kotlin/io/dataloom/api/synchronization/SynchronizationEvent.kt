package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable fact emitted about synchronization execution activity.
 *
 * [SynchronizationEvent] is a sealed contract with one variant per observable
 * lifecycle moment. Events are produced and emitted by the future DataLoom
 * runtime; applications receive them through a
 * [io.dataloom.api.observation.SynchronizationObserver].
 *
 * ## Conceptual event ordering
 *
 * Events are expected to follow runtime emission order:
 *
 * ```text
 * Started
 *     ↓
 * PhaseChanged (one or more)
 *     ↓
 * ProgressUpdated (zero or more)
 *     ↓
 * RetryScheduled | ConflictDetected (zero or more, when applicable)
 *     ↓
 * Completed
 * ```
 *
 * [Started] is emitted before any operational phases begin. [Completed] is
 * always the terminal event for a given synchronization request.
 *
 * ## Boundary with WorkflowLifecycleState and SynchronizationResult
 *
 * | Type                    | Meaning                                          |
 * |-------------------------|--------------------------------------------------|
 * | `WorkflowLifecycleState`| High-level workflow state (QUEUED, RUNNING, …)   |
 * | `SynchronizationPhase`  | Current operation within an executing workflow   |
 * | `SynchronizationEvent`  | Immutable fact emitted about execution           |
 * | `SynchronizationResult` | Terminal outcome                                 |
 *
 * Events describe runtime activity; they do not control it. Observer callbacks
 * are notifications, not commands.
 *
 * ## Common requirements
 *
 * - Events are immutable.
 * - Event IDs are not generated automatically.
 * - Event timestamps are not read automatically.
 * - Metadata defaults to [DataLoomMetadata.Empty].
 * - Events do not log themselves.
 * - `toString()` does not expose payload bytes.
 *
 * ## Future Flow observation
 *
 * A dedicated runtime-observation issue will introduce a Flow-based API after
 * event delivery semantics are approved:
 *
 * ```kotlin
 * fun observe(workflowId: WorkflowId): Flow<SynchronizationEvent>
 * ```
 *
 * This issue defines contracts only. Event dispatch infrastructure, observer
 * registration, replay, and hot/cold stream behavior are deferred.
 */
public sealed interface SynchronizationEvent {

    /**
     * Unique identifier for this event instance.
     *
     * Supplied by the future runtime; not generated automatically.
     */
    public val id: SynchronizationEventId

    /**
     * The synchronization request that this event belongs to.
     *
     * Must not expose credential, token, or payload content.
     */
    public val request: SynchronizationRequest

    /**
     * The instant at which this event occurred.
     *
     * Supplied by the future runtime; construction does not read the clock.
     */
    public val occurredAt: DataLoomInstant

    /**
     * Optional caller-supplied context attached to this event.
     *
     * Must not contain credentials, tokens, encryption keys, or personal data.
     * Defaults to [DataLoomMetadata.Empty].
     */
    public val metadata: DataLoomMetadata

    /**
     * The synchronization runtime has accepted the request for execution.
     *
     * [Started] is the first event emitted for a given synchronization request.
     * It represents runtime acceptance, not the completion of provider
     * operations. Provider operations may not have started when this event is
     * emitted.
     *
     * @param id Unique event identifier supplied by the runtime.
     * @param request The synchronization request that started.
     * @param occurredAt The instant at which the runtime accepted the request.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class Started(
        override val id: SynchronizationEventId,
        override val request: SynchronizationRequest,
        override val occurredAt: DataLoomInstant,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationEvent

    /**
     * The synchronization execution phase has changed.
     *
     * [PhaseChanged] is emitted when the runtime moves from one
     * [SynchronizationPhase] to another. It does not perform the phase
     * transition itself.
     *
     * @param id Unique event identifier supplied by the runtime.
     * @param request The synchronization request whose phase changed.
     * @param occurredAt The instant at which the phase change occurred.
     * @param phase The new synchronization phase.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class PhaseChanged(
        override val id: SynchronizationEventId,
        override val request: SynchronizationRequest,
        override val occurredAt: DataLoomInstant,
        public val phase: SynchronizationPhase,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationEvent

    /**
     * Synchronization execution progress has been updated.
     *
     * [ProgressUpdated] is emitted when the runtime has a meaningful progress
     * update to report. It does not accumulate progress itself.
     *
     * @param id Unique event identifier supplied by the runtime.
     * @param request The synchronization request whose progress updated.
     * @param occurredAt The instant at which the progress update occurred.
     * @param progress The current progress snapshot.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class ProgressUpdated(
        override val id: SynchronizationEventId,
        override val request: SynchronizationRequest,
        override val occurredAt: DataLoomInstant,
        public val progress: SynchronizationProgress,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationEvent

    /**
     * The runtime has scheduled a retry attempt for the current request.
     *
     * [RetryScheduled] reports a retry decision already made by the future
     * runtime. Creating this event does not schedule work, does not delay
     * execution, does not call [io.dataloom.api.scheduling.SchedulerProvider],
     * and does not reschedule queue entries.
     *
     * ## Retry boundary
     *
     * - `RetryPolicy` produces a `RetryDecision`.
     * - The runtime schedules or persists retry work.
     * - [RetryScheduled] reports the resulting decision.
     * - The event does not evaluate policy.
     * - The event does not call `SchedulerProvider`.
     * - The event does not reschedule a queue entry.
     *
     * @param id Unique event identifier supplied by the runtime.
     * @param request The synchronization request being retried.
     * @param occurredAt The instant at which the retry was scheduled.
     * @param attempt The retry attempt descriptor for this scheduled retry.
     * @param delay The minimum delay before the retry will be attempted.
     * @param error The canonical error that triggered the retry.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class RetryScheduled(
        override val id: SynchronizationEventId,
        override val request: SynchronizationRequest,
        override val occurredAt: DataLoomInstant,
        public val attempt: RetryAttempt,
        public val delay: SchedulingDelay,
        public val error: DataLoomError,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationEvent

    /**
     * A synchronization conflict has been detected.
     *
     * [ConflictDetected] reports a conflict identified by the runtime. Creating
     * this event does not run [io.dataloom.api.conflict.ConflictDetector] or
     * [io.dataloom.api.conflict.ConflictResolver].
     *
     * ## Conflict boundary
     *
     * - `ConflictDetector` identifies a conflict.
     * - The runtime emits [ConflictDetected].
     * - `ConflictResolver` produces a decision.
     * - The runtime applies the decision.
     * - Event observers do not resolve conflicts.
     * - Conflict payloads must not be logged automatically.
     *
     * @param id Unique event identifier supplied by the runtime.
     * @param request The synchronization request in which the conflict was
     *   detected.
     * @param occurredAt The instant at which the conflict was detected.
     * @param conflict The detected synchronization conflict.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class ConflictDetected(
        override val id: SynchronizationEventId,
        override val request: SynchronizationRequest,
        override val occurredAt: DataLoomInstant,
        public val conflict: SynchronizationConflict,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationEvent

    /**
     * The synchronization workflow has reached its terminal state.
     *
     * [Completed] is always the last event emitted for a given synchronization
     * request. It carries the terminal [SynchronizationResult].
     *
     * Creating this event does not complete queue work or publish itself.
     *
     * ## Constraints
     *
     * - [request] must equal [result.request][SynchronizationResult.request].
     * - [occurredAt] must not be earlier than
     *   [result.completedAt][SynchronizationResult.completedAt].
     *
     * @param id Unique event identifier supplied by the runtime.
     * @param request The synchronization request that completed. Must equal
     *   [result.request].
     * @param occurredAt The instant at which this event was emitted. Must not
     *   be earlier than [result.completedAt].
     * @param result The terminal result of the synchronization workflow.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class Completed(
        override val id: SynchronizationEventId,
        override val request: SynchronizationRequest,
        override val occurredAt: DataLoomInstant,
        public val result: SynchronizationResult,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationEvent {
        init {
            require(request == result.request) {
                "SynchronizationEvent.Completed request must equal result.request."
            }
            require(occurredAt.epochMilliseconds >= result.completedAt.epochMilliseconds) {
                "SynchronizationEvent.Completed occurredAt must not be earlier than result.completedAt."
            }
        }
    }
}
