package io.dataloom.transport.grpc

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
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
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit and in-process integration tests for [GrpcTransportProvider].
 *
 * Tests cover:
 * - push success (returns [ProviderOperationResult.Success] with [ChangeSetAcknowledgement])
 * - pull success with [PullChangesResult.Changes] including hasMore and checkpoint
 * - pull with no changes ([PullChangesResult.NoChanges]) including optional next checkpoint
 * - transport failure mapped to [ProviderOperationResult.Failure] with canonical [GrpcDataLoomError]
 * - gRPC status code → [GrpcDataLoomError] mapping
 * - coroutine cancellation is never swallowed
 * - in-process gRPC channel integration
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrpcTransportProviderTest {

    // ── Fixtures ─────────────────────────────────────────────────────────

    private val serverName = "grpc-transport-test-${System.nanoTime()}"
    private lateinit var inProcessChannel: ManagedChannel
    private lateinit var inProcessServer: io.grpc.Server

    private val syncRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private val changeEventId = ChangeEventId("evt-001")
    private val changeSetId = ChangeSetId("cs-001")

    private val singleEvent = ChangeEvent(
        id = changeEventId,
        entity = EntityReference(
            type = EntityType("order"),
            id = EntityId("entity-001"),
        ),
        operation = ChangeOperation.UPDATE,
    )

    private val changeSet = ChangeSet(
        id = changeSetId,
        events = listOf(singleEvent),
    )

    private val acknowledgement = ChangeSetAcknowledgement(
        changeSetId = changeSetId,
        events = listOf(
            ChangeEventAcknowledgement(
                eventId = changeEventId,
                status = ChangeAcknowledgementStatus.ACCEPTED,
            ),
        ),
    )

    private val checkpoint = SynchronizationCheckpoint(
        key = CheckpointKey("orders"),
        token = CheckpointToken("cursor-42"),
    )

    // ── In-process gRPC server setup ──────────────────────────────────────

    /**
     * A minimal string/string [MethodDescriptor] used for the in-process
     * integration test. This avoids a protoc dependency while exercising real
     * gRPC transport infrastructure.
     */
    private val pingMethodDescriptor: MethodDescriptor<String, String> =
        MethodDescriptor.newBuilder(StringMarshaller, StringMarshaller)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.TestService/Ping")
            .build()

    /** Simple marshaller that converts Strings to/from UTF-8 bytes. */
    private object StringMarshaller : MethodDescriptor.Marshaller<String> {
        override fun stream(value: String): java.io.InputStream =
            value.byteInputStream(Charsets.UTF_8)

        override fun parse(stream: java.io.InputStream): String =
            stream.reader(Charsets.UTF_8).readText()
    }

    @BeforeTest
    fun setUp() {
        val serviceDefinition = ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                pingMethodDescriptor,
                ServerCalls.asyncUnaryCall { request, responseObserver ->
                    responseObserver.onNext("pong:$request")
                    responseObserver.onCompleted()
                },
            )
            .build()

        inProcessServer = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(serviceDefinition)
            .build()
            .start()

        inProcessChannel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()
    }

    @AfterTest
    fun tearDown() {
        inProcessChannel.shutdownNow()
        inProcessServer.shutdownNow()
    }

    // ── Helper ────────────────────────────────────────────────────────────

    /**
     * Creates a [GrpcTransportProvider] concrete instance whose [executePushUnary]
     * and [executePullUnary] are supplied as lambdas. Allows per-test control
     * of outcomes without a full gRPC server for each scenario.
     */
    private fun provider(
        channel: ManagedChannel = inProcessChannel,
        pushImpl: suspend (PushChangesRequest) -> ChangeSetAcknowledgement = { acknowledgement },
        pullImpl: suspend (PullChangesRequest) -> PullChangesResult = {
            PullChangesResult.NoChanges()
        },
    ): GrpcTransportProvider = object : GrpcTransportProvider(channel) {

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("test.grpc.transport"),
            name = ProviderName("Test gRPC Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun executePushUnary(
            request: PushChangesRequest,
        ): ChangeSetAcknowledgement = pushImpl(request)

        override suspend fun executePullUnary(
            request: PullChangesRequest,
        ): PullChangesResult = pullImpl(request)
    }

    // ── Push success ──────────────────────────────────────────────────────

    @Test
    fun `pushChanges returns Success with ChangeSetAcknowledgement on success`() = runTest {
        val provider = provider(pushImpl = { acknowledgement })
        val request = PushChangesRequest(request = syncRequest, changeSet = changeSet)

        val result = provider.pushChanges(request)

        assertIs<ProviderOperationResult.Success<ChangeSetAcknowledgement>>(result)
        assertEquals(changeSetId, result.value.changeSetId)
        assertEquals(1, result.value.events.size)
        assertEquals(
            ChangeAcknowledgementStatus.ACCEPTED,
            result.value.events.first().status,
        )
    }

    // ── Pull success — changes with hasMore and checkpoint ────────────────

    @Test
    fun `pullChanges returns Success with Changes including hasMore and checkpoint`() = runTest {
        val changesResult = PullChangesResult.Changes(
            changeSet = changeSet,
            hasMore = true,
            nextCheckpoint = checkpoint,
        )
        val provider = provider(pullImpl = { changesResult })
        val request = PullChangesRequest(request = syncRequest)

        val result = provider.pullChanges(request)

        assertIs<ProviderOperationResult.Success<PullChangesResult>>(result)
        val changes = assertIs<PullChangesResult.Changes>(result.value)
        assertTrue(changes.hasMore)
        assertEquals(checkpoint, changes.nextCheckpoint)
        assertEquals(changeSetId, changes.changeSet.id)
    }

    @Test
    fun `pullChanges returns Success with NoChanges including optional checkpoint`() = runTest {
        val noChanges = PullChangesResult.NoChanges(nextCheckpoint = checkpoint)
        val provider = provider(pullImpl = { noChanges })
        val request = PullChangesRequest(request = syncRequest)

        val result = provider.pullChanges(request)

        assertIs<ProviderOperationResult.Success<PullChangesResult>>(result)
        val noChangesResult = assertIs<PullChangesResult.NoChanges>(result.value)
        assertEquals(checkpoint, noChangesResult.nextCheckpoint)
    }

    @Test
    fun `pullChanges returns Success with NoChanges and null checkpoint`() = runTest {
        val provider = provider(pullImpl = { PullChangesResult.NoChanges() })
        val request = PullChangesRequest(request = syncRequest)

        val result = provider.pullChanges(request)

        assertIs<ProviderOperationResult.Success<PullChangesResult>>(result)
        val noChanges = assertIs<PullChangesResult.NoChanges>(result.value)
        assertEquals(null, noChanges.nextCheckpoint)
    }

    // ── Transport failure — StatusException ───────────────────────────────

    @Test
    fun `pushChanges returns Failure with GrpcDataLoomError on StatusException`() = runTest {
        val provider = provider(pushImpl = { throw StatusException(Status.UNAVAILABLE) })
        val request = PushChangesRequest(request = syncRequest, changeSet = changeSet)

        val result = provider.pushChanges(request)

        assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<GrpcDataLoomError>(result.error)
        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals("GRPC_UNAVAILABLE", error.code.value)
    }

    @Test
    fun `pullChanges returns Failure with GrpcDataLoomError on StatusException`() = runTest {
        val provider = provider(pullImpl = { throw StatusException(Status.NOT_FOUND) })
        val request = PullChangesRequest(request = syncRequest)

        val result = provider.pullChanges(request)

        assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<GrpcDataLoomError>(result.error)
        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_NOT_FOUND", error.code.value)
    }

    // ── Transport failure — StatusRuntimeException ────────────────────────

    @Test
    fun `pushChanges returns Failure with GrpcDataLoomError on StatusRuntimeException`() = runTest {
        val provider = provider(
            pushImpl = { throw StatusRuntimeException(Status.INTERNAL) },
        )
        val request = PushChangesRequest(request = syncRequest, changeSet = changeSet)

        val result = provider.pushChanges(request)

        assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<GrpcDataLoomError>(result.error)
        assertEquals(ErrorCategory.INTERNAL, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals("GRPC_INTERNAL", error.code.value)
        assertNotNull(error.cause)
    }

    @Test
    fun `pullChanges returns Failure with GrpcDataLoomError on StatusRuntimeException`() = runTest {
        val provider = provider(
            pullImpl = { throw StatusRuntimeException(Status.UNAUTHENTICATED) },
        )
        val request = PullChangesRequest(request = syncRequest)

        val result = provider.pullChanges(request)

        assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<GrpcDataLoomError>(result.error)
        assertEquals(ErrorCategory.AUTHENTICATION, error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
    }

    // ── Cancellation is never swallowed ───────────────────────────────────

    @Test
    fun `pushChanges propagates CancellationException and does not swallow it`() = runTest {
        val provider = provider(pushImpl = { throw CancellationException("cancelled") })
        val request = PushChangesRequest(request = syncRequest, changeSet = changeSet)

        assertFailsWith<CancellationException> {
            provider.pushChanges(request)
        }
    }

    @Test
    fun `pullChanges propagates CancellationException and does not swallow it`() = runTest {
        val provider = provider(pullImpl = { throw CancellationException("cancelled") })
        val request = PullChangesRequest(request = syncRequest)

        assertFailsWith<CancellationException> {
            provider.pullChanges(request)
        }
    }

    // ── In-process gRPC channel integration ──────────────────────────────

    /**
     * Verifies that a [GrpcTransportProvider] subclass can make a real unary
     * gRPC call through an in-process channel. The subclass uses
     * [ClientCalls.blockingUnaryCall] to call the minimal test service set up
     * in [setUp].
     *
     * This test confirms that the gRPC transport infrastructure (channel
     * lifecycle, in-process transport) works end-to-end.
     */
    @Test
    fun `in-process channel integration — push uses real gRPC infrastructure`() = runTest {
        val capturedResponse = mutableListOf<String>()

        val inProcessProvider = object : GrpcTransportProvider(inProcessChannel) {

            override val descriptor: ProviderDescriptor = ProviderDescriptor(
                id = ProviderId("test.grpc.inprocess"),
                name = ProviderName("In-Process gRPC Transport"),
                type = ProviderType.TRANSPORT,
                version = ProviderVersion("1.0.0"),
            )

            override suspend fun initialize(
                context: ProviderInitializationContext,
            ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

            override suspend fun health(): ProviderOperationResult<ProviderHealth> =
                ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

            override suspend fun close(): ProviderOperationResult<Unit> =
                ProviderOperationResult.Success(Unit)

            override suspend fun executePushUnary(
                request: PushChangesRequest,
            ): ChangeSetAcknowledgement {
                // Make a real gRPC call using the in-process channel.
                val call = channel.newCall(pingMethodDescriptor, CallOptions.DEFAULT)
                val response = ClientCalls.blockingUnaryCall(
                    call,
                    "push:${request.changeSet.id.value}",
                )
                capturedResponse.add(response)
                return acknowledgement
            }

            override suspend fun executePullUnary(
                request: PullChangesRequest,
            ): PullChangesResult = PullChangesResult.NoChanges()
        }

        val pushRequest = PushChangesRequest(request = syncRequest, changeSet = changeSet)
        val result = inProcessProvider.pushChanges(pushRequest)

        assertIs<ProviderOperationResult.Success<ChangeSetAcknowledgement>>(result)
        assertEquals(1, capturedResponse.size)
        assertTrue(capturedResponse.first().startsWith("pong:push:"))
    }

    // ── gRPC status codes → canonical error mapping coverage ─────────────

    @Test
    fun `DEADLINE_EXCEEDED maps to NETWORK RECOVERABLE error`() = runTest {
        val provider = provider(
            pushImpl = { throw StatusException(Status.DEADLINE_EXCEEDED) },
        )
        val request = PushChangesRequest(request = syncRequest, changeSet = changeSet)

        val result = provider.pushChanges(request)

        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(ErrorCategory.NETWORK, result.error.category)
        assertEquals(Recoverability.RECOVERABLE, result.error.recoverability)
    }

    @Test
    fun `PERMISSION_DENIED maps to AUTHORIZATION NON_RECOVERABLE error`() = runTest {
        val provider = provider(
            pullImpl = { throw StatusRuntimeException(Status.PERMISSION_DENIED) },
        )
        val request = PullChangesRequest(request = syncRequest)

        val result = provider.pullChanges(request)

        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(ErrorCategory.AUTHORIZATION, result.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
    }
}
