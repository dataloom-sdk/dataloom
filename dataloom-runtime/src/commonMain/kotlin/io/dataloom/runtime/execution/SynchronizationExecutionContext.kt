package io.dataloom.runtime.execution

import io.dataloom.api.execution.SynchronizationProviderSet
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.runtime.execution.lifecycle.SynchronizationLifecycleEventEmitter

/**
 * Immutable execution context passed to a [SynchronizationPipeline] by the
 * [SynchronizationExecutionCoordinator].
 *
 * ## Purpose
 *
 * [SynchronizationExecutionContext] groups the inputs required by every
 * synchronization pipeline:
 *
 * - The original [SynchronizationRequest] that triggered the execution.
 * - The [ResolvedSynchronizationProviders] container produced by provider
 *   resolution.
 * - The [RuntimeDependencies] instance injected into the coordinator.
 * - An optional [SynchronizationLifecycleEventEmitter] for emitting phase
 *   events at deterministic operation boundaries.
 *
 * ## Construction restrictions
 *
 * Construction performs no clock read, no identifier generation, no provider
 * lifecycle operation, no provider operation, and no synchronization work.
 * It does not access any registry, resolver, or coordinator.
 *
 * ## Immutability
 *
 * All properties are preserved exactly as supplied. No copy, mutation, or
 * transformation is performed. No mutable collection is exposed.
 *
 * ## Backward compatibility
 *
 * The [lifecycleEventEmitter] parameter defaults to `null`. Existing callers
 * that construct [SynchronizationExecutionContext] without a lifecycle emitter
 * continue to work unchanged. When `null`, pipelines skip phase event emission.
 *
 * ## Security restrictions
 *
 * [toString] must not invoke any provider implementation's `toString()` method.
 * It must not expose provider internal state, payload bytes, checkpoint tokens,
 * credentials, encryption keys, personal data, or stack traces.
 *
 * The diagnostic representation may include:
 * - The synchronization request identifier (session ID and workflow ID).
 * - The synchronization direction from the request.
 * - Provider IDs or types from the resolved providers.
 * - Whether a lifecycle emitter is configured.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and core types only. Safe for
 * use in Kotlin Multiplatform common code.
 *
 * @param request the [SynchronizationRequest] that triggered this execution.
 * @param providers the fully resolved provider set produced by
 *   [io.dataloom.core.provider.SynchronizationProviderResolver].
 * @param runtimeDependencies the [RuntimeDependencies] instance injected into
 *   the coordinator.
 * @param lifecycleEventEmitter the optional [SynchronizationLifecycleEventEmitter]
 *   used to emit phase-changed events at deterministic operation boundaries.
 *   When `null`, phase events are not emitted. Defaults to `null` for
 *   backward compatibility.
 */
public class SynchronizationExecutionContext(
    /** The synchronization request that triggered this execution. */
    public val request: SynchronizationRequest,

    /** The fully resolved synchronization provider set. */
    public val providers: SynchronizationProviderSet,

    /** The runtime dependencies injected into the coordinator. */
    public val runtimeDependencies: RuntimeDependencies,

    /**
     * Optional lifecycle event emitter for phase-changed events.
     *
     * When `null`, pipelines skip phase event emission. When configured,
     * pipelines call [SynchronizationLifecycleEventEmitter.emitPhaseChanged]
     * immediately before each supported provider operation.
     */
    public val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter? = null,
) {

    /**
     * Returns a safe diagnostic representation.
     *
     * Includes the request session ID, workflow ID, direction, and provider
     * IDs from the resolved provider set. Does not invoke any provider
     * implementation's `toString()` and does not expose provider internal
     * state, credentials, payloads, checkpoint tokens, encryption keys, or
     * personal data.
     */
    override fun toString(): String {
        val storageId = providers.storageProvider.descriptor.id.value
        val transportId = providers.transportProvider.descriptor.id.value
        val schedulerId = providers.schedulerProvider?.descriptor?.id?.value
        val connectivityId = providers.connectivityProvider?.descriptor?.id?.value
        val queueId = providers.queueProvider?.descriptor?.id?.value
        return "SynchronizationExecutionContext(" +
            "sessionId=${request.sessionId.value}, " +
            "workflowId=${request.workflowId.value}, " +
            "direction=${request.direction}, " +
            "storage=$storageId, " +
            "transport=$transportId, " +
            "scheduler=${schedulerId ?: "null"}, " +
            "connectivity=${connectivityId ?: "null"}, " +
            "queue=${queueId ?: "null"}" +
            ")"
    }
}
