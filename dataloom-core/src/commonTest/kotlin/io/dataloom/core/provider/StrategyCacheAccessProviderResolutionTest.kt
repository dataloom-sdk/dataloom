package io.dataloom.core.provider

import io.dataloom.api.provider.ProviderBindingFailureReason
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StrategyCacheAccessProviderResolutionTest {

    @Test
    fun cacheAccessCapabilityRejectsPlainStorageProvider() {
        val storage = RecordingStorageProvider()
        val resolver = StrategyProviderResolver(ProviderRegistry(listOf(storage)))

        val result = resolver.resolve(
            bindings = StrategyProviderBindings(storageProviderId = storage.descriptor.id),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
            ),
        )

        val failure = assertIs<StrategyProviderResolutionResult.Failure>(result)
        assertTrue(failure.missingCapabilities.isEmpty())
        assertEquals(1, failure.bindingFailures.size)
        assertEquals(
            ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH,
            failure.bindingFailures.single().reason,
        )
        assertEquals(0, storage.operationCalls)
    }

    @Test
    fun cacheAccessProviderResolvesWithoutInvocation() {
        val storage = RecordingCacheAccessProvider()
        val resolver = StrategyProviderResolver(ProviderRegistry(listOf(storage)))

        val result = resolver.resolve(
            bindings = StrategyProviderBindings(storageProviderId = storage.descriptor.id),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
            ),
        )

        val success = assertIs<StrategyProviderResolutionResult.Success>(result)
        assertSame(storage, success.providers.storageProvider)
        assertEquals(0, storage.operationCalls)
        assertEquals(0, storage.cacheAccessCalls)
    }

    private open class RecordingStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage"),
            name = ProviderName("Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        var operationCalls: Int = 0

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> = unexpected()

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = unexpected()

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = unexpected()

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = unexpected()

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = unexpected()

        protected fun <T> unexpected(): T {
            operationCalls++
            error("Provider operations must not run during resolution.")
        }
    }

    private class RecordingCacheAccessProvider :
        RecordingStorageProvider(),
        StrategyCacheAccessProvider {
        var cacheAccessCalls: Int = 0

        override suspend fun evaluateCacheAccess(
            request: StrategyCacheAccessRequest,
        ): ProviderOperationResult<StrategyCacheAccessResult> {
            cacheAccessCalls++
            error("Cache access must not run during resolution.")
        }
    }
}
