package io.dataloom.runtime.retry

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class StorageTransportProviderTimeoutTest {

    @Test
    fun `storage descriptor and completed read result are preserved exactly`() = runTest {
        val expected = ProviderOperationResult.Success<OutboundChangeReadResult>(
            OutboundChangeReadResult.NoChanges,
        )
        val delegate = RecordingStorageProvider(readResult = expected)
        val provider = protectedStorage(delegate, 1_000L)

        assertSame(delegate.descriptor, provider.descriptor)
        assertSame(expected, provider.readOutboundChanges(outboundReadRequest))
        assertEquals(1, delegate.readCalls)
    }

    @Test
    fun `zero storage read timeout is recoverable and prevents delegate invocation`() = runTest {
        val delegate = RecordingStorageProvider()

        val failure = assertIs<ProviderOperationResult.Failure>(
            protectedStorage(delegate, 0L).readOutboundChanges(outboundReadRequest),
        )

        assertEquals(0, delegate.readCalls)
        assertEquals("STORAGE_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(ErrorCategory.STORAGE, failure.error.category)
        assertEquals(Recoverability.RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `zero storage mutation timeout is unknown and prevents delegate invocation`() = runTest {
        val delegate = RecordingStorageProvider()

        val failure = assertIs<ProviderOperationResult.Failure>(
            protectedStorage(delegate, 0L).applyInboundChanges(inboundApplyRequest),
        )

        assertEquals(0, delegate.applyCalls)
        assertEquals("STORAGE_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
        assertTrue(failure.error.message.contains("durable completion is not confirmed"))
    }

    @Test
    fun `storage mutation timeout executes cooperative cleanup`() = runTest {
        val delegate = RecordingStorageProvider(delayMilliseconds = 1_000L)

        val failure = assertIs<ProviderOperationResult.Failure>(
            protectedStorage(delegate, 100L).applyInboundChanges(inboundApplyRequest),
        )

        assertEquals(1, delegate.applyCalls)
        assertTrue(delegate.applyFinallyExecuted)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
    }

    @Test
    fun `transport descriptor and canonical failure are preserved exactly`() = runTest {
        val expected = TestError(
            code = ErrorCode("REMOTE_REJECTED"),
            category = ErrorCategory.NETWORK,
        )
        val delegate = RecordingTransportProvider(
            pushResult = ProviderOperationResult.Failure(expected),
        )
        val provider = protectedTransport(delegate, 1_000L)

        assertSame(delegate.descriptor, provider.descriptor)
        val failure = assertIs<ProviderOperationResult.Failure>(
            provider.pushChanges(pushRequest),
        )
        assertSame(expected, failure.error)
        assertEquals(1, delegate.pushCalls)
    }

    @Test
    fun `zero transport push timeout is unknown and prevents delegate invocation`() = runTest {
        val delegate = RecordingTransportProvider()

        val failure = assertIs<ProviderOperationResult.Failure>(
            protectedTransport(delegate, 0L).pushChanges(pushRequest),
        )

        assertEquals(0, delegate.pushCalls)
        assertEquals("TRANSPORT_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(ErrorCategory.NETWORK, failure.error.category)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
        assertTrue(failure.error.message.contains("remote completion is not confirmed"))
    }

    @Test
    fun `zero transport pull timeout remains unknown without idempotency evidence`() = runTest {
        val delegate = RecordingTransportProvider()

        val failure = assertIs<ProviderOperationResult.Failure>(
            protectedTransport(delegate, 0L).pullChanges(pullRequest),
        )

        assertEquals(0, delegate.pullCalls)
        assertEquals("TRANSPORT_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
    }

    @Test
    fun `read-only transport health timeout remains recoverable`() = runTest {
        val delegate = RecordingTransportProvider(delayMilliseconds = 1_000L)

        val failure = assertIs<ProviderOperationResult.Failure>(
            protectedTransport(delegate, 100L).health(),
        )

        assertEquals(1, delegate.healthCalls)
        assertEquals("TRANSPORT_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(ErrorCategory.PROVIDER, failure.error.category)
        assertEquals(Recoverability.RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `caller cancellation propagates from transport push`() = runTest {
        val delegate = RecordingTransportProvider(delayMilliseconds = 10_000L)
        val provider = protectedTransport(delegate, 20_000L)
        val execution = backgroundScope.async {
            provider.pushChanges(pushRequest)
        }
        delegate.pushStarted.await()

        execution.cancel(CancellationException("caller cancelled"))
        val failure = captureFailure { execution.await() }

        assertIs<CancellationException>(failure)
        assertEquals("caller cancelled", failure.message)
        assertEquals(1, delegate.pushCalls)
        assertTrue(delegate.pushFinallyExecuted)
    }

    @Test
    fun `production timeout assembly is side effect free`() {
        val clock = CountingClock()
        val storage = RecordingStorageProvider()
        val transport = RecordingTransportProvider()

        StorageProviderTimeoutRuntime.create(
            storageProvider = storage,
            clock = clock,
            providerTimeout = SchedulingDelay(1_000L),
        )
        TransportProviderTimeoutRuntime.create(
            transportProvider = transport,
            clock = clock,
            providerTimeout = SchedulingDelay(1_000L),
        )

        assertEquals(0, clock.calls)
        assertEquals(0, storage.totalCalls)
        assertEquals(0, transport.totalCalls)
    }

    private fun protectedStorage(
        delegate: StorageProvider,
        timeoutMilliseconds: Long,
    ): StorageProvider = StorageProviderTimeoutRuntime.create(
        storageProvider = delegate,
        clock = FixedClock,
        providerTimeout = SchedulingDelay(timeoutMilliseconds),
    )

    private fun protectedTransport(
        delegate: TransportProvider,
        timeoutMilliseconds: Long,
    ): TransportProvider = TransportProviderTimeoutRuntime.create(
        transportProvider = delegate,
        clock = FixedClock,
        providerTimeout = SchedulingDelay(timeoutMilliseconds),
    )

    private class RecordingStorageProvider(
        private val delayMilliseconds: Long = 0L,
        private val readResult: ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage-timeout-test"),
            name = ProviderName("Storage Timeout Test"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        var initializeCalls: Int = 0
            private set
        var healthCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set
        var readCalls: Int = 0
            private set
        var applyCalls: Int = 0
            private set
        var acknowledgeCalls: Int = 0
            private set
        var checkpointReadCalls: Int = 0
            private set
        var checkpointWriteCalls: Int = 0
            private set
        var applyFinallyExecuted: Boolean = false
            private set

        val totalCalls: Int
            get() = initializeCalls + healthCalls + closeCalls + readCalls + applyCalls +
                acknowledgeCalls + checkpointReadCalls + checkpointWriteCalls

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            readCalls++
            waitIfConfigured()
            return readResult
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyCalls++
            return try {
                waitIfConfigured()
                ProviderOperationResult.Success(Unit)
            } finally {
                applyFinallyExecuted = true
            }
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            acknowledgeCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            checkpointReadCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(null)
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            checkpointWriteCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        private suspend fun waitIfConfigured() {
            if (delayMilliseconds > 0L) delay(delayMilliseconds)
        }
    }

    private class RecordingTransportProvider(
        private val delayMilliseconds: Long = 0L,
        private val pushResult: ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(TestError()),
        private val pullResult: ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(TestError()),
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-timeout-test"),
            name = ProviderName("Transport Timeout Test"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        var initializeCalls: Int = 0
            private set
        var healthCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set
        var pushCalls: Int = 0
            private set
        var pullCalls: Int = 0
            private set
        var pushFinallyExecuted: Boolean = false
            private set
        val pushStarted: CompletableDeferred<Unit> = CompletableDeferred()

        val totalCalls: Int
            get() = initializeCalls + healthCalls + closeCalls + pushCalls + pullCalls

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCalls++
            waitIfConfigured()
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCalls++
            pushStarted.complete(Unit)
            return try {
                waitIfConfigured()
                pushResult
            } finally {
                pushFinallyExecuted = true
            }
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            waitIfConfigured()
            return pullResult
        }

        private suspend fun waitIfConfigured() {
            if (delayMilliseconds > 0L) delay(delayMilliseconds)
        }
    }

    private class CountingClock : DataLoomClock {
        var calls: Int = 0
            private set

        override fun now(): DataLoomInstant {
            calls++
            return now
        }
    }

    private data class TestError(
        override val code: ErrorCode = ErrorCode("PROVIDER_TEST_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Provider test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try {
        block()
        error("Expected block to fail.")
    } catch (failure: Throwable) {
        failure
    }

    private object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = now
    }

    private companion object {
        val now = DataLoomInstant(1_000L)
        val synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("provider-timeout-workflow"),
            sessionId = SynchronizationSessionId("provider-timeout-session"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("provider-timeout-execution"),
                correlationId = CorrelationId("provider-timeout-correlation"),
            ),
        )
        val changeSet = ChangeSet(
            id = ChangeSetId("provider-timeout-change-set"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("provider-timeout-change"),
                    entity = EntityReference(
                        type = EntityType("document"),
                        id = EntityId("document-1"),
                    ),
                    operation = ChangeOperation.UPDATE,
                ),
            ),
        )
        val outboundReadRequest = OutboundChangeReadRequest(synchronizationRequest)
        val inboundApplyRequest = InboundChangeApplyRequest(
            request = synchronizationRequest,
            changeSet = changeSet,
        )
        val pushRequest = PushChangesRequest(
            request = synchronizationRequest,
            changeSet = changeSet,
        )
        val pullRequest = PullChangesRequest(
            request = synchronizationRequest,
        )
    }
}
