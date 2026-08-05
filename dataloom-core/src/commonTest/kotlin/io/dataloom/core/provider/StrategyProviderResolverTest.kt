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
import io.dataloom.api.strategy.StrategyOfflineFirstAdmissionProvider
import io.dataloom.api.strategy.StrategyOfflineFirstAdmissionRequest
import io.dataloom.api.strategy.StrategyOfflineFirstAdmissionResult
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StrategyProviderResolverTest {
    private open class RecordingStorageProvider(
        id: String = "storage",
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            error("Provider operations must not run during resolution.")

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> =
            error("Provider operations must not run during resolution.")

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> =
            error("Provider operations must not run during resolution.")

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> =
            error("Provider operations must not run during resolution.")

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> =
            error("Provider operations must not run during resolution.")
    }

    private class AtomicRecordingStorageProvider :
        RecordingStorageProvider("atomic-storage"),
        StrategyOfflineFirstAdmissionProvider {
        override suspend fun admitLocalIntentAndOutbox(
            request: StrategyOfflineFirstAdmissionRequest,
        ): ProviderOperationResult<StrategyOfflineFirstAdmissionResult> =
            error("Provider operations must not run during resolution.")
    }

    private class RecordingTransportProvider : TransportProvider {
        var initializeCalls: Int = 0
        var healthCalls: Int = 0
        var closeCalls: Int = 0
        var pushCalls: Int = 0
        var pullCalls: Int = 0

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport"),
            name = ProviderName("Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            return ProviderOperationResult.Success(
                ProviderHealth(ProviderHealthStatus.HEALTHY),
            )
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCalls++
            error("Provider operations must not run during resolution.")
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            error("Provider operations must not run during resolution.")
        }
    }

    @Test
    fun transportOnlyResolutionIgnoresExtraStorageAndQueueBindings() {
        val transport = RecordingTransportProvider()
        val resolver = StrategyProviderResolver(ProviderRegistry(listOf(transport)))

        val result = resolver.resolve(
            bindings = StrategyProviderBindings(
                storageProviderId = ProviderId("missing-storage"),
                transportProviderId = transport.descriptor.id,
                queueProviderId = ProviderId("missing-queue"),
            ),
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
        )

        val success = assertIs<StrategyProviderResolutionResult.Success>(result)
        assertSame(transport, success.providers.transportProvider)
        assertNull(success.providers.storageProvider)
        assertNull(success.providers.queueProvider)
        assertEquals(0, transport.initializeCalls)
        assertEquals(0, transport.healthCalls)
        assertEquals(0, transport.closeCalls)
        assertEquals(0, transport.pushCalls)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun missingRequiredTransportIsReportedWithoutPartialProviderSet() {
        val transport = RecordingTransportProvider()
        val resolver = StrategyProviderResolver(ProviderRegistry(listOf(transport)))

        val result = resolver.resolve(
            bindings = StrategyProviderBindings(),
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
        )

        val failure = assertIs<StrategyProviderResolutionResult.Failure>(result)
        assertEquals(setOf(StrategyProviderCapability.TRANSPORT), failure.missingCapabilities)
        assertTrue(failure.bindingFailures.isEmpty())
    }

    @Test
    fun wrongProviderTypeProducesCanonicalBindingFailure() {
        val transport = RecordingTransportProvider()
        val resolver = StrategyProviderResolver(ProviderRegistry(listOf(transport)))

        val result = resolver.resolve(
            bindings = StrategyProviderBindings(
                storageProviderId = transport.descriptor.id,
            ),
            requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
        )

        val failure = assertIs<StrategyProviderResolutionResult.Failure>(result)
        assertTrue(failure.missingCapabilities.isEmpty())
        assertEquals(1, failure.bindingFailures.size)
        assertEquals(ProviderType.STORAGE, failure.bindingFailures.single().expectedType)
        assertEquals(ProviderType.TRANSPORT, failure.bindingFailures.single().actualType)
    }

    @Test
    fun atomicLocalAdmissionRequiresStorageExtensionContract() {
        val storage = RecordingStorageProvider()
        val resolver = StrategyProviderResolver(ProviderRegistry(listOf(storage)))

        val result = resolver.resolve(
            bindings = StrategyProviderBindings(storageProviderId = storage.descriptor.id),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.ATOMIC_LOCAL_ADMISSION,
            ),
        )

        val failure = assertIs<StrategyProviderResolutionResult.Failure>(result)
        assertEquals(
            ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH,
            failure.bindingFailures.single().reason,
        )
    }

    @Test
    fun atomicLocalAdmissionResolvesWithoutInvokingProvider() {
        val storage = AtomicRecordingStorageProvider()
        val resolver = StrategyProviderResolver(ProviderRegistry(listOf(storage)))

        val result = resolver.resolve(
            bindings = StrategyProviderBindings(storageProviderId = storage.descriptor.id),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.ATOMIC_LOCAL_ADMISSION,
            ),
        )

        val success = assertIs<StrategyProviderResolutionResult.Success>(result)
        assertSame(storage, success.providers.storageProvider)
    }
}
