package io.dataloom.runtime.execution

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.core.runtime.RuntimeDependencies

/**
 * Immutable execution context passed to a [SynchronizationPipeline] by the
 * [SynchronizationExecutionCoordinator].
 *
 * ## Purpose
 *
 * [SynchronizationExecutionContext] groups the three inputs required by every
 * synchronization pipeline:
 *
 * - The original [SynchronizationRequest] that triggered the execution.
 * - The [ResolvedSynchronizationProviders] container produced by provider
 *   resolution.
 * - The [RuntimeDependencies] instance injected into the coordinator.
 *
 * ## Construction restrictions
 *
 * Construction performs no clock read, no identifier generation, no provider
 * lifecycle operation, no provider operation, and no synchronization work.
 * It does not access any registry, resolver, or coordinator.
 *
 * ## Immutability
 *
 * All three properties are preserved exactly as supplied. No copy, mutation,
 * or transformation is performed. No mutable collection is exposed.
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
 */
public class SynchronizationExecutionContext(
    /** The synchronization request that triggered this execution. */
    public val request: SynchronizationRequest,

    /** The fully resolved synchronization provider set. */
    public val providers: ResolvedSynchronizationProviders,

    /** The runtime dependencies injected into the coordinator. */
    public val runtimeDependencies: RuntimeDependencies,
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
