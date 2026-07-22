package io.dataloom.core.provider

import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.QueueProvider
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-019 provider binding and resolution contracts.
 *
 * All fake providers are stateless and safe for use in common code. No real
 * database, real network, filesystem, Thread.sleep, arbitrary delay, Android API,
 * JVM-only reflection, ServiceLoader, production credentials, or personal data is used.
 *
 * Tests cover:
 * - [SynchronizationProviderBindings]
 * - [ProviderBindingFailureReason]
 * - [ProviderBindingFailure]
 * - [ResolvedSynchronizationProviders]
 * - [ProviderResolutionResult]
 * - [SynchronizationProviderResolver]
 */
class SynchronizationProviderResolverTest {

    // =========================================================================
    // Fake infrastructure
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    /** Minimal DataLoomProvider-only — does NOT implement any specialized interface. */
    private class FakeBaseProvider(
        id: String,
        type: ProviderType,
        var initializeCallCount: Int = 0,
        var closeCallCount: Int = 0,
        var healthCallCount: Int = 0,
    ) : DataLoomProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Fake $id"),
            type = type,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCallCount++
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }
    }

    /** Fake StorageProvider that tracks operations. */
    private class FakeStorageProvider(
        id: String,
        var initializeCallCount: Int = 0,
        var closeCallCount: Int = 0,
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage-$id"),
            name = ProviderName("Storage $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readOutboundChanges(request: OutboundChangeReadRequest): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun acknowledgeOutboundChanges(request: OutboundChangeAcknowledgementRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun readCheckpoint(request: CheckpointReadRequest): ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())
    }

    /** Fake TransportProvider that tracks operations. */
    private class FakeTransportProvider(
        id: String,
        var initializeCallCount: Int = 0,
        var closeCallCount: Int = 0,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-$id"),
            name = ProviderName("Transport $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(FakeError())
    }

    /** Fake SchedulerProvider that tracks operations. */
    private class FakeSchedulerProvider(id: String) : SchedulerProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler-$id"),
            name = ProviderName("Scheduler $id"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(request: ScheduleRequest): ProviderOperationResult<ScheduleReceipt> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun cancel(request: ScheduleCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())
    }

    /** Fake ConnectivityProvider that tracks operations. */
    private class FakeConnectivityProvider(id: String) : ConnectivityProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("connectivity-$id"),
            name = ProviderName("Connectivity $id"),
            type = ProviderType.CONNECTIVITY,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun currentConnectivity(request: ConnectivityCheckRequest): ProviderOperationResult<ConnectivitySnapshot> =
            ProviderOperationResult.Success(ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, null))
    }

    /** Fake QueueProvider that tracks operations. */
    private class FakeQueueProvider(id: String) : QueueProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("queue-$id"),
            name = ProviderName("Queue $id"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun recoverExpiredLeases(request: ExpiredLeaseRecoveryRequest): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Failure(FakeError())
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun storageId(suffix: String = "primary") = ProviderId("storage-$suffix")
    private fun transportId(suffix: String = "prod") = ProviderId("transport-$suffix")
    private fun schedulerId(suffix: String = "default") = ProviderId("scheduler-$suffix")
    private fun connectivityId(suffix: String = "default") = ProviderId("connectivity-$suffix")
    private fun queueId(suffix: String = "default") = ProviderId("queue-$suffix")

    private fun registryWith(vararg providers: DataLoomProvider) =
        ProviderRegistry(providers.toList())

    private fun resolver(vararg providers: DataLoomProvider) =
        SynchronizationProviderResolver(registryWith(*providers))

    private fun requiredBindings(
        storage: ProviderId = storageId(),
        transport: ProviderId = transportId(),
    ) = SynchronizationProviderBindings(
        storageProviderId = storage,
        transportProviderId = transport,
    )

    // =========================================================================
    // SynchronizationProviderBindings — construction
    // =========================================================================

    @Test
    fun `bindings preserves required storage and transport IDs`() {
        val sId = storageId()
        val tId = transportId()
        val bindings = SynchronizationProviderBindings(
            storageProviderId = sId,
            transportProviderId = tId,
        )

        assertEquals(sId, bindings.storageProviderId)
        assertEquals(tId, bindings.transportProviderId)
    }

    @Test
    fun `bindings optional fields default to null`() {
        val bindings = requiredBindings()

        assertNull(bindings.schedulerProviderId)
        assertNull(bindings.connectivityProviderId)
        assertNull(bindings.queueProviderId)
    }

    @Test
    fun `bindings preserves optional scheduler provider ID`() {
        val sched = schedulerId()
        val bindings = SynchronizationProviderBindings(
            storageProviderId = storageId(),
            transportProviderId = transportId(),
            schedulerProviderId = sched,
        )

        assertEquals(sched, bindings.schedulerProviderId)
    }

    @Test
    fun `bindings preserves optional connectivity provider ID`() {
        val conn = connectivityId()
        val bindings = SynchronizationProviderBindings(
            storageProviderId = storageId(),
            transportProviderId = transportId(),
            connectivityProviderId = conn,
        )

        assertEquals(conn, bindings.connectivityProviderId)
    }

    @Test
    fun `bindings preserves optional queue provider ID`() {
        val queue = queueId()
        val bindings = SynchronizationProviderBindings(
            storageProviderId = storageId(),
            transportProviderId = transportId(),
            queueProviderId = queue,
        )

        assertEquals(queue, bindings.queueProviderId)
    }

    @Test
    fun `bindings with all optional IDs preserves every property`() {
        val sId = storageId()
        val tId = transportId()
        val schId = schedulerId()
        val cId = connectivityId()
        val qId = queueId()

        val bindings = SynchronizationProviderBindings(
            storageProviderId = sId,
            transportProviderId = tId,
            schedulerProviderId = schId,
            connectivityProviderId = cId,
            queueProviderId = qId,
        )

        assertEquals(sId, bindings.storageProviderId)
        assertEquals(tId, bindings.transportProviderId)
        assertEquals(schId, bindings.schedulerProviderId)
        assertEquals(cId, bindings.connectivityProviderId)
        assertEquals(qId, bindings.queueProviderId)
    }

    @Test
    fun `bindings provides value-based equality`() {
        val sId = storageId()
        val tId = transportId()
        val a = SynchronizationProviderBindings(storageProviderId = sId, transportProviderId = tId)
        val b = SynchronizationProviderBindings(storageProviderId = sId, transportProviderId = tId)

        assertEquals(a, b)
    }

    @Test
    fun `bindings construction performs no provider action`() {
        // Construction must not touch any provider — verified by absence of
        // any provider instances in this test; the data class is self-contained.
        val bindings = requiredBindings()
        assertNotNull(bindings)
    }

    // =========================================================================
    // ProviderBindingFailure — construction and properties
    // =========================================================================

    @Test
    fun `ProviderBindingFailure preserves missing-provider failure properties`() {
        val id = storageId()
        val failure = ProviderBindingFailure(
            requestedId = id,
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )

        assertEquals(id, failure.requestedId)
        assertEquals(ProviderType.STORAGE, failure.expectedType)
        assertNull(failure.actualType)
        assertEquals(ProviderBindingFailureReason.PROVIDER_NOT_FOUND, failure.reason)
    }

    @Test
    fun `ProviderBindingFailure preserves type-mismatch failure properties`() {
        val id = transportId()
        val failure = ProviderBindingFailure(
            requestedId = id,
            expectedType = ProviderType.STORAGE,
            actualType = ProviderType.TRANSPORT,
            reason = ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH,
        )

        assertEquals(id, failure.requestedId)
        assertEquals(ProviderType.STORAGE, failure.expectedType)
        assertEquals(ProviderType.TRANSPORT, failure.actualType)
        assertEquals(ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH, failure.reason)
    }

    @Test
    fun `ProviderBindingFailure preserves contract-mismatch failure properties`() {
        val id = storageId()
        val failure = ProviderBindingFailure(
            requestedId = id,
            expectedType = ProviderType.STORAGE,
            actualType = ProviderType.STORAGE,
            reason = ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH,
        )

        assertEquals(id, failure.requestedId)
        assertEquals(ProviderType.STORAGE, failure.expectedType)
        assertEquals(ProviderType.STORAGE, failure.actualType)
        assertEquals(ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH, failure.reason)
    }

    @Test
    fun `ProviderBindingFailure actualType can be null for missing provider`() {
        val failure = ProviderBindingFailure(
            requestedId = storageId(),
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )

        assertNull(failure.actualType)
    }

    @Test
    fun `ProviderBindingFailure provides value-based equality`() {
        val id = storageId()
        val a = ProviderBindingFailure(
            requestedId = id,
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )
        val b = ProviderBindingFailure(
            requestedId = id,
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )

        assertEquals(a, b)
    }

    @Test
    fun `ProviderBindingFailure toString does not expose provider instance`() {
        val failure = ProviderBindingFailure(
            requestedId = storageId(),
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )

        val repr = failure.toString()
        assertTrue(repr.contains("ProviderBindingFailure"), "toString should identify the type")
        assertTrue(repr.contains("storage-primary"), "toString should contain the requested ID")
        assertTrue(repr.contains("STORAGE"), "toString should contain the expected type")
        assertTrue(repr.contains("PROVIDER_NOT_FOUND"), "toString should contain the reason")
    }

    // =========================================================================
    // ProviderResolutionResult — construction
    // =========================================================================

    @Test
    fun `ProviderResolutionResult Success contains resolved providers`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolved = ResolvedSynchronizationProviders(
            storageProvider = storage,
            transportProvider = transport,
            schedulerProvider = null,
            connectivityProvider = null,
            queueProvider = null,
        )

        val result = ProviderResolutionResult.Success(resolved)

        assertIs<ProviderResolutionResult.Success>(result)
        assertSame(resolved, result.providers)
    }

    @Test
    fun `ProviderResolutionResult Failure rejects empty failure list`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderResolutionResult.Failure(emptyList())
        }
    }

    @Test
    fun `ProviderResolutionResult Failure accepts non-empty failure list`() {
        val failure = ProviderBindingFailure(
            requestedId = storageId(),
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )

        val result = ProviderResolutionResult.Failure(listOf(failure))

        assertIs<ProviderResolutionResult.Failure>(result)
        assertEquals(1, result.bindingFailures.size)
    }

    @Test
    fun `ProviderResolutionResult Failure defensively copies source collection`() {
        val failure = ProviderBindingFailure(
            requestedId = storageId(),
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )
        val source = mutableListOf(failure)
        val result = ProviderResolutionResult.Failure(source)

        source.clear() // mutate source after construction

        assertEquals(1, result.bindingFailures.size)
        assertEquals(failure, result.bindingFailures[0])
    }

    @Test
    fun `ProviderResolutionResult Failure preserves failure order`() {
        val storageFailure = ProviderBindingFailure(
            requestedId = storageId(),
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )
        val transportFailure = ProviderBindingFailure(
            requestedId = transportId(),
            expectedType = ProviderType.TRANSPORT,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )

        val result = ProviderResolutionResult.Failure(listOf(storageFailure, transportFailure))

        assertEquals(storageFailure, result.bindingFailures[0])
        assertEquals(transportFailure, result.bindingFailures[1])
    }

    @Test
    fun `ProviderResolutionResult Failure bindingFailures is immutable List`() {
        val failure = ProviderBindingFailure(
            requestedId = storageId(),
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )

        val result = ProviderResolutionResult.Failure(listOf(failure))
        val list: List<ProviderBindingFailure> = result.bindingFailures

        assertEquals(1, list.size)
    }

    @Test
    fun `ProviderResolutionResult Failure provides value-based equality`() {
        val failure = ProviderBindingFailure(
            requestedId = storageId(),
            expectedType = ProviderType.STORAGE,
            actualType = null,
            reason = ProviderBindingFailureReason.PROVIDER_NOT_FOUND,
        )

        val a = ProviderResolutionResult.Failure(listOf(failure))
        val b = ProviderResolutionResult.Failure(listOf(failure))

        assertEquals(a, b)
    }

    // =========================================================================
    // Successful resolution
    // =========================================================================

    @Test
    fun `required storage and transport providers resolve successfully`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Success>(result)
        assertSame(storage, result.providers.storageProvider)
        assertSame(transport, result.providers.transportProvider)
    }

    @Test
    fun `optional providers remain null when not configured`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Success>(result)
        assertNull(result.providers.schedulerProvider)
        assertNull(result.providers.connectivityProvider)
        assertNull(result.providers.queueProvider)
    }

    @Test
    fun `all optional providers resolve when configured`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val scheduler = FakeSchedulerProvider("sch")
        val connectivity = FakeConnectivityProvider("conn")
        val queue = FakeQueueProvider("q")
        val resolver = resolver(storage, transport, scheduler, connectivity, queue)
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-s"),
            transportProviderId = ProviderId("transport-t"),
            schedulerProviderId = ProviderId("scheduler-sch"),
            connectivityProviderId = ProviderId("connectivity-conn"),
            queueProviderId = ProviderId("queue-q"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Success>(result)
        assertSame(storage, result.providers.storageProvider)
        assertSame(transport, result.providers.transportProvider)
        assertSame(scheduler, result.providers.schedulerProvider)
        assertSame(connectivity, result.providers.connectivityProvider)
        assertSame(queue, result.providers.queueProvider)
    }

    @Test
    fun `exact registered instances are returned`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Success>(result)
        assertSame(storage, result.providers.storageProvider)
        assertSame(transport, result.providers.transportProvider)
    }

    @Test
    fun `multiple providers of same type - explicit ProviderId determines selection`() {
        val storagePrimary = FakeStorageProvider("primary")
        val storageSecondary = FakeStorageProvider("secondary")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storagePrimary, storageSecondary, transport)

        // Explicitly select secondary storage
        val bindings = requiredBindings(
            storage = ProviderId("storage-secondary"),
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Success>(result)
        assertSame(storageSecondary, result.providers.storageProvider)
    }

    @Test
    fun `multiple transport providers - explicit ProviderId determines selection`() {
        val storage = FakeStorageProvider("s")
        val transportProd = FakeTransportProvider("production")
        val transportTest = FakeTransportProvider("test")
        val resolver = resolver(storage, transportProd, transportTest)

        // Explicitly select test transport
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("transport-test"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Success>(result)
        assertSame(transportTest, result.providers.transportProvider)
    }

    @Test
    fun `registration order does not override explicit bindings`() {
        // Registration order: secondary first, primary second
        val storageSecondary = FakeStorageProvider("secondary")
        val storagePrimary = FakeStorageProvider("primary")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storageSecondary, storagePrimary, transport)

        // Explicitly bind to primary (registered second)
        val bindings = requiredBindings(
            storage = ProviderId("storage-primary"),
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Success>(result)
        assertSame(storagePrimary, result.providers.storageProvider)
    }

    // =========================================================================
    // Missing providers
    // =========================================================================

    @Test
    fun `missing required storage provider produces PROVIDER_NOT_FOUND failure`() {
        val transport = FakeTransportProvider("t")
        val resolver = resolver(transport)
        val bindings = requiredBindings(
            storage = ProviderId("storage-missing"),
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val failure = result.bindingFailures.first { it.expectedType == ProviderType.STORAGE }
        assertEquals(ProviderBindingFailureReason.PROVIDER_NOT_FOUND, failure.reason)
        assertEquals(ProviderId("storage-missing"), failure.requestedId)
        assertNull(failure.actualType)
    }

    @Test
    fun `missing required transport provider produces PROVIDER_NOT_FOUND failure`() {
        val storage = FakeStorageProvider("s")
        val resolver = resolver(storage)
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("transport-missing"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val failure = result.bindingFailures.first { it.expectedType == ProviderType.TRANSPORT }
        assertEquals(ProviderBindingFailureReason.PROVIDER_NOT_FOUND, failure.reason)
        assertEquals(ProviderId("transport-missing"), failure.requestedId)
    }

    @Test
    fun `missing optional scheduler when configured produces PROVIDER_NOT_FOUND failure`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-s"),
            transportProviderId = ProviderId("transport-t"),
            schedulerProviderId = ProviderId("scheduler-missing"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val failure = result.bindingFailures.first { it.expectedType == ProviderType.SCHEDULER }
        assertEquals(ProviderBindingFailureReason.PROVIDER_NOT_FOUND, failure.reason)
    }

    @Test
    fun `missing optional connectivity when configured produces PROVIDER_NOT_FOUND failure`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-s"),
            transportProviderId = ProviderId("transport-t"),
            connectivityProviderId = ProviderId("connectivity-missing"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val failure = result.bindingFailures.first { it.expectedType == ProviderType.CONNECTIVITY }
        assertEquals(ProviderBindingFailureReason.PROVIDER_NOT_FOUND, failure.reason)
    }

    @Test
    fun `missing optional queue when configured produces PROVIDER_NOT_FOUND failure`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-s"),
            transportProviderId = ProviderId("transport-t"),
            queueProviderId = ProviderId("queue-missing"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val failure = result.bindingFailures.first { it.expectedType == ProviderType.QUEUE }
        assertEquals(ProviderBindingFailureReason.PROVIDER_NOT_FOUND, failure.reason)
    }

    @Test
    fun `all missing configured providers are reported in one result`() {
        val resolver = resolver() // empty registry
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-missing"),
            transportProviderId = ProviderId("transport-missing"),
            schedulerProviderId = ProviderId("scheduler-missing"),
            connectivityProviderId = ProviderId("connectivity-missing"),
            queueProviderId = ProviderId("queue-missing"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        assertEquals(5, result.bindingFailures.size)
        assertTrue(result.bindingFailures.all { it.reason == ProviderBindingFailureReason.PROVIDER_NOT_FOUND })
    }

    @Test
    fun `failure order follows role-validation order for missing providers`() {
        val resolver = resolver() // empty registry
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-missing"),
            transportProviderId = ProviderId("transport-missing"),
            schedulerProviderId = ProviderId("scheduler-missing"),
            connectivityProviderId = ProviderId("connectivity-missing"),
            queueProviderId = ProviderId("queue-missing"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val types = result.bindingFailures.map { it.expectedType }
        assertEquals(
            listOf(
                ProviderType.STORAGE,
                ProviderType.TRANSPORT,
                ProviderType.SCHEDULER,
                ProviderType.CONNECTIVITY,
                ProviderType.QUEUE,
            ),
            types,
        )
    }

    // =========================================================================
    // Type mismatch
    // =========================================================================

    @Test
    fun `storage binding pointing to transport descriptor produces PROVIDER_TYPE_MISMATCH`() {
        // Register a transport provider but bind it as storage
        val transport = FakeTransportProvider("t")
        val resolver = resolver(transport)
        val bindings = requiredBindings(
            storage = ProviderId("transport-t"), // wrong: binding storage to a transport provider
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val storageFailure = result.bindingFailures.first { it.expectedType == ProviderType.STORAGE }
        assertEquals(ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH, storageFailure.reason)
        assertEquals(ProviderType.TRANSPORT, storageFailure.actualType)
    }

    @Test
    fun `transport binding pointing to storage descriptor produces PROVIDER_TYPE_MISMATCH`() {
        // Register a storage provider but bind it as transport
        val storage = FakeStorageProvider("s")
        val resolver = resolver(storage)
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("storage-s"), // wrong: binding transport to a storage provider
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val transportFailure = result.bindingFailures.first { it.expectedType == ProviderType.TRANSPORT }
        assertEquals(ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH, transportFailure.reason)
        assertEquals(ProviderType.STORAGE, transportFailure.actualType)
    }

    @Test
    fun `optional role binding pointing to wrong type produces PROVIDER_TYPE_MISMATCH`() {
        // Use a storage provider where scheduler is expected
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-s"),
            transportProviderId = ProviderId("transport-t"),
            schedulerProviderId = ProviderId("storage-s"), // wrong type
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val schedulerFailure = result.bindingFailures.first { it.expectedType == ProviderType.SCHEDULER }
        assertEquals(ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH, schedulerFailure.reason)
        assertEquals(ProviderType.STORAGE, schedulerFailure.actualType)
    }

    @Test
    fun `actual ProviderType is preserved in type-mismatch failure`() {
        val transport = FakeTransportProvider("t")
        val resolver = resolver(transport)
        val bindings = requiredBindings(
            storage = ProviderId("transport-t"), // wrong: should be storage
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val storageFailure = result.bindingFailures.first { it.expectedType == ProviderType.STORAGE }
        assertEquals(ProviderType.TRANSPORT, storageFailure.actualType)
    }

    // =========================================================================
    // Contract mismatch
    // =========================================================================

    /**
     * A DataLoomProvider whose descriptor declares STORAGE but that does NOT
     * implement StorageProvider.
     */
    private class FakeStorageDescriptorOnlyProvider(id: String) : DataLoomProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("StorageDescOnly $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /**
     * A DataLoomProvider whose descriptor declares TRANSPORT but that does NOT
     * implement TransportProvider.
     */
    private class FakeTransportDescriptorOnlyProvider(id: String) : DataLoomProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("TransportDescOnly $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /**
     * A DataLoomProvider whose descriptor declares SCHEDULER but that does NOT
     * implement SchedulerProvider.
     */
    private class FakeSchedulerDescriptorOnlyProvider(id: String) : DataLoomProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("SchedulerDescOnly $id"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /**
     * A DataLoomProvider whose descriptor declares CONNECTIVITY but that does NOT
     * implement ConnectivityProvider.
     */
    private class FakeConnectivityDescriptorOnlyProvider(id: String) : DataLoomProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("ConnectivityDescOnly $id"),
            type = ProviderType.CONNECTIVITY,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /**
     * A DataLoomProvider whose descriptor declares QUEUE but that does NOT
     * implement QueueProvider.
     */
    private class FakeQueueDescriptorOnlyProvider(id: String) : DataLoomProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("QueueDescOnly $id"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    @Test
    fun `STORAGE descriptor without StorageProvider produces PROVIDER_CONTRACT_MISMATCH`() {
        val fakeStorage = FakeStorageDescriptorOnlyProvider("s-desc-only")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(fakeStorage, transport)
        val bindings = requiredBindings(
            storage = ProviderId("s-desc-only"),
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val failure = result.bindingFailures.first { it.expectedType == ProviderType.STORAGE }
        assertEquals(ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH, failure.reason)
        assertEquals(ProviderType.STORAGE, failure.actualType)
    }

    @Test
    fun `TRANSPORT descriptor without TransportProvider produces PROVIDER_CONTRACT_MISMATCH`() {
        val storage = FakeStorageProvider("s")
        val fakeTransport = FakeTransportDescriptorOnlyProvider("t-desc-only")
        val resolver = resolver(storage, fakeTransport)
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("t-desc-only"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val failure = result.bindingFailures.first { it.expectedType == ProviderType.TRANSPORT }
        assertEquals(ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH, failure.reason)
        assertEquals(ProviderType.TRANSPORT, failure.actualType)
    }

    @Test
    fun `optional provider contract mismatch is reported`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val fakeScheduler = FakeSchedulerDescriptorOnlyProvider("sch-desc-only")
        val fakeConnectivity = FakeConnectivityDescriptorOnlyProvider("conn-desc-only")
        val fakeQueue = FakeQueueDescriptorOnlyProvider("q-desc-only")
        val resolver = resolver(storage, transport, fakeScheduler, fakeConnectivity, fakeQueue)
        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-s"),
            transportProviderId = ProviderId("transport-t"),
            schedulerProviderId = ProviderId("sch-desc-only"),
            connectivityProviderId = ProviderId("conn-desc-only"),
            queueProviderId = ProviderId("q-desc-only"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val reasons = result.bindingFailures.map { it.reason }
        assertTrue(
            reasons.all { it == ProviderBindingFailureReason.PROVIDER_CONTRACT_MISMATCH },
            "All optional failures should be contract mismatches, got: $reasons",
        )
    }

    @Test
    fun `type mismatch is evaluated before contract mismatch`() {
        // A provider registered as TRANSPORT but bound to STORAGE role.
        // Its descriptor says TRANSPORT, so type mismatch fires before any interface check.
        val transport = FakeTransportProvider("t")
        val resolver = resolver(transport)
        val bindings = requiredBindings(
            storage = ProviderId("transport-t"),
            transport = ProviderId("transport-t"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        val storageFailure = result.bindingFailures.first { it.expectedType == ProviderType.STORAGE }
        assertEquals(ProviderBindingFailureReason.PROVIDER_TYPE_MISMATCH, storageFailure.reason)
    }

    // =========================================================================
    // Side-effect boundary
    // =========================================================================

    @Test
    fun `resolution performs no provider initialization`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("transport-t"),
        )

        resolver.resolve(bindings)

        assertEquals(0, storage.initializeCallCount)
        assertEquals(0, transport.initializeCallCount)
    }

    @Test
    fun `resolution performs no provider shutdown`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("transport-t"),
        )

        resolver.resolve(bindings)

        assertEquals(0, storage.closeCallCount)
        assertEquals(0, transport.closeCallCount)
    }

    @Test
    fun `resolution invokes no provider operation on failure`() {
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val resolver = resolver(storage, transport)
        // Wrong IDs — will fail
        val bindings = requiredBindings(
            storage = ProviderId("storage-missing"),
            transport = ProviderId("transport-missing"),
        )

        resolver.resolve(bindings)

        assertEquals(0, storage.initializeCallCount)
        assertEquals(0, transport.initializeCallCount)
        assertEquals(0, storage.closeCallCount)
        assertEquals(0, transport.closeCallCount)
    }

    @Test
    fun `resolution does not expose partially resolved providers on failure`() {
        // Storage resolves, transport fails
        val storage = FakeStorageProvider("s")
        val resolver = resolver(storage)
        val bindings = requiredBindings(
            storage = ProviderId("storage-s"),
            transport = ProviderId("transport-missing"),
        )

        val result = resolver.resolve(bindings)

        assertIs<ProviderResolutionResult.Failure>(result)
        // Failure must not expose any provider instance
    }

    // =========================================================================
    // Compatibility assertions
    // =========================================================================

    @Test
    fun `no Android API is required - resolver is constructed from pure Kotlin types`() {
        // This test is a compile-time assertion: if this file compiles in
        // commonTest without errors, no Android, JVM-only, or platform APIs are used.
        val storage = FakeStorageProvider("s")
        val transport = FakeTransportProvider("t")
        val registry = ProviderRegistry(listOf(storage, transport))
        val resolver = SynchronizationProviderResolver(registry)
        assertNotNull(resolver)
    }

    @Test
    fun `resolver requires no external registry or service locator`() {
        // The resolver receives the registry explicitly.
        val registry = ProviderRegistry(emptyList())
        val resolver = SynchronizationProviderResolver(registry)
        assertNotNull(resolver)
    }

    @Test
    fun `SynchronizationProviderBindings requires no external lookup on construction`() {
        // Data class construction must be side-effect free.
        val bindings = SynchronizationProviderBindings(
            storageProviderId = storageId(),
            transportProviderId = transportId(),
        )
        assertNotNull(bindings)
    }
}
