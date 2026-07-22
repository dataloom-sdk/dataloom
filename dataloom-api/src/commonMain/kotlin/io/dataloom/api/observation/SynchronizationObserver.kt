package io.dataloom.api.observation

import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.synchronization.SynchronizationEvent

/**
 * Application-level contract for receiving synchronization lifecycle events.
 *
 * A [SynchronizationObserver] is registered with the future DataLoom runtime
 * to receive [SynchronizationEvent] notifications as a synchronization
 * workflow executes. The runtime controls event ordering and delivery.
 *
 * ## Observation semantics
 *
 * - Events describe runtime activity; they do not control it.
 * - Observer callbacks are notifications, not commands.
 * - Event ordering is expected to follow runtime emission order.
 * - Cross-thread delivery guarantees are not defined in this issue.
 * - Replay is not implemented in this issue.
 * - Persistent event history is not implemented in this issue.
 * - Multiple-observer registration is not implemented in this issue.
 * - Observer failure isolation is not implemented in this issue.
 * - Backpressure is not implemented in this issue.
 * - Lifecycle-aware collection is not implemented in this issue.
 * - Flow-based observation is deferred to a future issue.
 *
 * ## Implementation guidelines
 *
 * Implementations should:
 * - Return quickly from [onEvent].
 * - Avoid blocking the calling thread.
 * - Avoid throwing exceptions; the future runtime will isolate observer
 *   failures so that one failing observer cannot fail synchronization or
 *   prevent delivery to other observers.
 * - Not log payload content automatically.
 * - Not mutate DataLoom runtime state directly.
 * - Not claim guaranteed exactly-once event delivery, which is not defined
 *   in this issue.
 *
 * ## Platform independence
 *
 * This interface depends only on the Kotlin standard library and existing
 * DataLoom API types. It must not depend on Android lifecycle types, Hilt,
 * Koin, Dagger, or another DI framework, and is safe for use in Kotlin
 * Multiplatform common code.
 *
 * ## Future Flow observation boundary
 *
 * A dedicated runtime-observation issue will introduce a Flow-based API:
 *
 * ```kotlin
 * fun observe(workflowId: WorkflowId): Flow<SynchronizationEvent>
 * ```
 *
 * Do not add `kotlinx-coroutines-core` or expose `Flow` types in DL-016.
 * Buffer, overflow, and backpressure behavior are deferred.
 *
 * ## Monitoring and logging boundary
 *
 * | Component                 | Responsibility                                    |
 * |---------------------------|---------------------------------------------------|
 * | `SynchronizationObserver` | Application-level event observation contract      |
 * | `MonitoringProvider`      | Future metrics and telemetry integration (deferred)|
 * | `LoggingProvider`         | Future structured logging integration (deferred)  |
 *
 * These have different responsibilities and must not be conflated. The runtime
 * may adapt lifecycle events into metrics or logs later through separate
 * provider integrations.
 */
public interface SynchronizationObserver {

    /**
     * Unique identifier for this observer.
     *
     * Supplied by the host application or integration. Not generated
     * automatically.
     */
    public val id: SynchronizationObserverId

    /**
     * Called by the future runtime when a [SynchronizationEvent] is available.
     *
     * The supplied [event] is immutable. Implementations must not mutate
     * DataLoom runtime state through the event or through any side channel
     * triggered by receiving it.
     *
     * Implementations should return quickly and must not block the calling
     * thread. If long-running work is required, it should be dispatched
     * asynchronously by the implementation.
     *
     * Implementations must not throw. The future runtime will isolate observer
     * failures so that one failing observer cannot fail synchronization or
     * prevent delivery to other observers. That isolation behavior is
     * documented here but is not implemented in this issue.
     *
     * Payload content must not be logged automatically from within
     * [onEvent].
     *
     * Thread-safety: implementations are responsible for their own thread
     * safety. The calling thread is determined by the future runtime and is
     * not defined in this issue.
     *
     * @param event The immutable synchronization lifecycle event to handle.
     */
    public fun onEvent(event: SynchronizationEvent)
}
