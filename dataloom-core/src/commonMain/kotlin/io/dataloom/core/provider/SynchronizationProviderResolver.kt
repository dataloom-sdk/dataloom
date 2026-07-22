package io.dataloom.core.provider

import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.transport.TransportProvider

/**
 * Resolves explicit provider bindings declared in [SynchronizationProviderBindings]
 * against a [ProviderRegistry].
 *
 * ## Purpose
 *
 * [SynchronizationProviderResolver] validates that every configured
 * [io.dataloom.api.provider.ProviderId] in a [SynchronizationProviderBindings]
 * refers to a registered provider of the correct [ProviderType] and correct
 * specialized provider interface.
 *
 * On success it returns a [ProviderResolutionResult.Success] containing the
 * exact provider instances from the registry. On failure it returns a
 * [ProviderResolutionResult.Failure] containing all binding failures in
 * deterministic role order.
 *
 * ## Explicit ProviderId selection
 *
 * Every lookup is based on the exact [ProviderId] configured in the bindings.
 * No provider is selected by [ProviderType] alone, by registration order, or
 * by any naming convention.
 *
 * ## Multiple providers of the same ProviderType
 *
 * The registry may contain multiple providers that share a [ProviderType].
 * [SynchronizationProviderResolver] always resolves the exact instance bound
 * by [ProviderId], regardless of how many providers share the same type.
 *
 * ## Role validation
 *
 * For each configured binding, the resolver validates:
 *
 * 1. The provider exists in the registry ([ProviderBindingFailureReason.PROVIDER_NOT_FOUND]).
 * 2. The provider's [io.dataloom.api.provider.ProviderDescriptor.type] matches
 *    the expected [ProviderType] for the role
 *    ([ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH]).
 * 3. The provider implements the required specialized interface for the role
 *    ([ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH]).
 *
 * Type mismatch is evaluated before contract mismatch.
 *
 * ## Deterministic failure ordering
 *
 * All configured roles are evaluated. Failures are collected and returned in
 * deterministic order: Storage, Transport, Scheduler, Connectivity, Queue.
 * Optional roles that are not configured produce no failure.
 *
 * ## No partial resolution
 *
 * When any binding fails, [ProviderResolutionResult.Failure] is returned and
 * no provider instances are exposed through the result.
 *
 * ## Explicit injection
 *
 * [ProviderRegistry] is supplied at construction time. There is no global
 * registry, no service locator, and no reflection.
 *
 * ## Lifecycle boundary
 *
 * [SynchronizationProviderResolver] performs no provider lifecycle operation.
 * It does not initialize, shut down, or health-check any provider. The future
 * synchronization runtime is responsible for ensuring lifecycle initialization
 * has completed before using resolved providers.
 *
 * ## No synchronization
 *
 * This resolver performs no synchronization execution, retry orchestration,
 * queue processing, conflict resolution, event dispatch, scheduling, or
 * connectivity observation.
 *
 * ## Thread safety
 *
 * [SynchronizationProviderResolver] is stateless after construction and safe
 * to call from any thread or coroutine context. It selects no dispatcher and
 * exposes no [kotlinx.coroutines.CoroutineScope].
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and core types only. No
 * Android, JVM-only, Apple-specific, or third-party APIs are required.
 *
 * ## Security restrictions
 *
 * Diagnostic representations must not expose provider internal state,
 * credentials, authorization headers, payload bytes, checkpoint tokens,
 * encryption keys, or personal data.
 *
 * @param registry the [ProviderRegistry] to search when resolving bindings.
 */
public class SynchronizationProviderResolver(
    private val registry: ProviderRegistry,
) {

    /**
     * Resolves all provider bindings declared in [bindings] against the
     * registry supplied at construction time.
     *
     * Each configured role is validated in the following deterministic order:
     *
     * 1. Storage
     * 2. Transport
     * 3. Scheduler
     * 4. Connectivity
     * 5. Queue
     *
     * Optional roles that are not configured are skipped and produce no
     * failure. Required roles are always evaluated.
     *
     * Returns [ProviderResolutionResult.Success] when all configured bindings
     * are valid, containing the exact registered provider instances.
     *
     * Returns [ProviderResolutionResult.Failure] when one or more configured
     * bindings fail, containing all failures in role-validation order. No
     * provider instance is exposed in the failure result.
     *
     * This method invokes no provider lifecycle method, no provider operation,
     * and performs no synchronization work.
     *
     * @param bindings the explicit provider ID bindings to resolve.
     * @return [ProviderResolutionResult.Success] or [ProviderResolutionResult.Failure].
     */
    public fun resolve(
        bindings: SynchronizationProviderBindings,
    ): ProviderResolutionResult {
        val failures = mutableListOf<ProviderBindingFailure>()

        // --- Storage (required) ---
        val storageProvider = resolveRole<StorageProvider>(
            id = bindings.storageProviderId,
            expectedType = ProviderType.STORAGE,
            failures = failures,
        )

        // --- Transport (required) ---
        val transportProvider = resolveRole<TransportProvider>(
            id = bindings.transportProviderId,
            expectedType = ProviderType.TRANSPORT,
            failures = failures,
        )

        // --- Scheduler (optional) ---
        val schedulerProvider = bindings.schedulerProviderId?.let { id ->
            resolveRole<SchedulerProvider>(
                id = id,
                expectedType = ProviderType.SCHEDULER,
                failures = failures,
            )
        }

        // --- Connectivity (optional) ---
        val connectivityProvider = bindings.connectivityProviderId?.let { id ->
            resolveRole<ConnectivityProvider>(
                id = id,
                expectedType = ProviderType.CONNECTIVITY,
                failures = failures,
            )
        }

        // --- Queue (optional) ---
        val queueProvider = bindings.queueProviderId?.let { id ->
            resolveRole<QueueProvider>(
                id = id,
                expectedType = ProviderType.QUEUE,
                failures = failures,
            )
        }

        if (failures.isNotEmpty()) {
            return ProviderResolutionResult.Failure(failures)
        }

        return ProviderResolutionResult.Success(
            ResolvedSynchronizationProviders(
                storageProvider = storageProvider!!,
                transportProvider = transportProvider!!,
                schedulerProvider = schedulerProvider,
                connectivityProvider = connectivityProvider,
                queueProvider = queueProvider,
            ),
        )
    }

    /**
     * Resolves a single role binding.
     *
     * Validates existence, descriptor type, and provider interface in order.
     * Records any failure in [failures] and returns `null` on failure,
     * or the validated provider on success.
     *
     * Type checking uses direct Kotlin `is` checks without reflection,
     * [Class.forName], [kotlin.reflect.KClass], or [java.util.ServiceLoader].
     */
    private inline fun <reified T : DataLoomProvider> resolveRole(
        id: ProviderId,
        expectedType: ProviderType,
        failures: MutableList<ProviderBindingFailure>,
    ): T? {
        val provider = registry.findById(id)

        if (provider == null) {
            failures.add(
                ProviderBindingFailure(
                    requestedId = id,
                    expectedType = expectedType,
                    actualType = null,
                    reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
                ),
            )
            return null
        }

        val actualType = provider.descriptor.type

        if (actualType != expectedType) {
            failures.add(
                ProviderBindingFailure(
                    requestedId = id,
                    expectedType = expectedType,
                    actualType = actualType,
                    reason = ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH,
                ),
            )
            return null
        }

        if (provider !is T) {
            failures.add(
                ProviderBindingFailure(
                    requestedId = id,
                    expectedType = expectedType,
                    actualType = actualType,
                    reason = ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH,
                ),
            )
            return null
        }

        return provider
    }
}
