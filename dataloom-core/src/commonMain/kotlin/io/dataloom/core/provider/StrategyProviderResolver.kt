package io.dataloom.core.provider

import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderBindingFailure
import io.dataloom.api.provider.ProviderBindingFailureReason
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.transport.TransportProvider

/**
 * Resolves only provider-backed capabilities required by an evaluated plan.
 *
 * Extra bindings are deliberately ignored. In particular, a network-only plan
 * that requires only transport never looks up storage or queue bindings.
 */
public class StrategyProviderResolver(
    private val registry: ProviderRegistry,
) {
    public fun resolve(
        bindings: StrategyProviderBindings,
        requiredCapabilities: Set<StrategyProviderCapability>,
    ): StrategyProviderResolutionResult {
        val missing = mutableSetOf<StrategyProviderCapability>()
        val failures = mutableListOf<ProviderBindingFailure>()

        val storage = resolveRequiredRole<StorageProvider>(
            capability = StrategyProviderCapability.STORAGE,
            requiredCapabilities = requiredCapabilities,
            id = bindings.storageProviderId,
            expectedType = ProviderType.STORAGE,
            missing = missing,
            failures = failures,
        )
        val transport = resolveRequiredRole<TransportProvider>(
            capability = StrategyProviderCapability.TRANSPORT,
            requiredCapabilities = requiredCapabilities,
            id = bindings.transportProviderId,
            expectedType = ProviderType.TRANSPORT,
            missing = missing,
            failures = failures,
        )
        val queue = resolveRequiredRole<QueueProvider>(
            capability = StrategyProviderCapability.QUEUE,
            requiredCapabilities = requiredCapabilities,
            id = bindings.queueProviderId,
            expectedType = ProviderType.QUEUE,
            missing = missing,
            failures = failures,
        )
        val connectivity = resolveRequiredRole<ConnectivityProvider>(
            capability = StrategyProviderCapability.CONNECTIVITY,
            requiredCapabilities = requiredCapabilities,
            id = bindings.connectivityProviderId,
            expectedType = ProviderType.CONNECTIVITY,
            missing = missing,
            failures = failures,
        )
        val scheduler = resolveRequiredRole<SchedulerProvider>(
            capability = StrategyProviderCapability.SCHEDULER,
            requiredCapabilities = requiredCapabilities,
            id = bindings.schedulerProviderId,
            expectedType = ProviderType.SCHEDULER,
            missing = missing,
            failures = failures,
        )

        if (missing.isNotEmpty() || failures.isNotEmpty()) {
            return StrategyProviderResolutionResult.Failure(
                missingCapabilities = missing,
                bindingFailures = failures,
            )
        }

        return StrategyProviderResolutionResult.Success(
            providers = ResolvedStrategyProviders(
                storageProvider = storage,
                transportProvider = transport,
                schedulerProvider = scheduler,
                connectivityProvider = connectivity,
                queueProvider = queue,
            ),
        )
    }

    private inline fun <reified T : DataLoomProvider> resolveRequiredRole(
        capability: StrategyProviderCapability,
        requiredCapabilities: Set<StrategyProviderCapability>,
        id: ProviderId?,
        expectedType: ProviderType,
        missing: MutableSet<StrategyProviderCapability>,
        failures: MutableList<ProviderBindingFailure>,
    ): T? {
        if (capability !in requiredCapabilities) return null
        if (id == null) {
            missing += capability
            return null
        }

        val provider = registry.findById(id)
        if (provider == null) {
            failures += ProviderBindingFailure(
                requestedId = id,
                expectedType = expectedType,
                actualType = null,
                reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
            )
            return null
        }
        if (provider.descriptor.type != expectedType) {
            failures += ProviderBindingFailure(
                requestedId = id,
                expectedType = expectedType,
                actualType = provider.descriptor.type,
                reason = ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH,
            )
            return null
        }
        if (provider !is T) {
            failures += ProviderBindingFailure(
                requestedId = id,
                expectedType = expectedType,
                actualType = provider.descriptor.type,
                reason = ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH,
            )
            return null
        }
        return provider
    }
}
