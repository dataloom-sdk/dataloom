package io.dataloom.runtime.facade

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder

/**
 * Immutable configuration for [DataLoomBuilder] queue-submission assembly.
 *
 * ## Encoder
 *
 * [encoder] converts an application submission into the exact
 * [io.dataloom.api.queue.QueueEnqueueRequest] persisted by the queue provider.
 * It is invoked only when [io.dataloom.runtime.submission.DataLoomQueueSubmission.submit]
 * is called; constructing this spec or building the runtime never invokes it.
 *
 * ## Queue-provider timeout
 *
 * [queueProviderTimeout] optionally applies the production cooperative provider
 * timeout to the single `QueueProvider.enqueue` call made by a submission.
 *
 * - `null` preserves the historical direct enqueue path.
 * - [SchedulingDelay.ZERO] rejects before provider invocation.
 * - a positive value cooperatively cancels an in-flight enqueue operation.
 *
 * A timed-out enqueue may already have committed durably. It is therefore
 * reported as `Recoverability.UNKNOWN` and is never replayed automatically.
 * Callers must reuse the stable queue-entry identifier and reconcile according
 * to provider idempotency policy before deciding whether to submit again.
 *
 * Construction performs no encoding, clock read, queue operation, timeout
 * execution, identifier generation, or coroutine launch.
 */
public class DataLoomQueueSubmissionSpec(
    /** Application-owned encoder used for one queue-submission call. */
    public val encoder: QueuedSynchronizationWorkEncoder,

    /** Optional timeout applied only to the queue-provider enqueue operation. */
    public val queueProviderTimeout: SchedulingDelay?,
) {
    /** Preserves the historical direct enqueue behavior with no timeout. */
    public constructor(
        encoder: QueuedSynchronizationWorkEncoder,
    ) : this(
        encoder = encoder,
        queueProviderTimeout = null,
    )
}
