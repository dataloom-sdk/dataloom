package io.dataloom.transport.ktor

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.RetryDelayHintCarrier
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
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.PayloadContentType
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorTransportProviderTest {
    @Test
    fun `push success returns decoded acknowledgement`() = runTest {
        val provider: KtorTransportProvider = providerWithEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://example.test/push", request.url.toString())
            assertEquals("******", request.headers[HttpHeaders.Authorization])
            respond(
                content = "accepted:event-1",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val result: ProviderOperationResult<ChangeSetAcknowledgement> = provider.pushChanges(
            PushChangesRequest(
                request = sampleSynchronizationRequest(direction = SynchronizationDirection.PUSH),
                changeSet = sampleChangeSet(changeSetId = "change-set-1", eventId = "event-1"),
            ),
        )

        val success = assertIs<ProviderOperationResult.Success<ChangeSetAcknowledgement>>(result)
        assertEquals(ChangeSetId("change-set-1"), success.value.changeSetId)
        assertEquals(ChangeAcknowledgementStatus.ACCEPTED, success.value.events.single().status)
    }

    @Test
    fun `pull success returns decoded inbound changes`() = runTest {
        val provider: KtorTransportProvider = providerWithEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://example.test/pull", request.url.toString())
            respond(
                content = "changes:true:checkpoint-2:event-remote-1",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val result: ProviderOperationResult<PullChangesResult> = provider.pullChanges(
            PullChangesRequest(
                request = sampleSynchronizationRequest(direction = SynchronizationDirection.PULL),
                entityTypes = setOf(EntityType("widget")),
                maxEvents = 20,
                checkpoint = sampleCheckpoint("checkpoint-1"),
            ),
        )

        val success = assertIs<ProviderOperationResult.Success<PullChangesResult>>(result)
        val changes = assertIs<PullChangesResult.Changes>(success.value)
        assertTrue(changes.hasMore)
        assertEquals(ChangeSetId("remote-change-set"), changes.changeSet.id)
        assertEquals(ChangeEventId("event-remote-1"), changes.changeSet.events.single().id)
        assertEquals(CheckpointToken("checkpoint-2"), changes.nextCheckpoint?.token)
    }

    @Test
    fun `timeout failure is classified canonically`() = runTest {
        val provider: KtorTransportProvider = providerWithEngine { request ->
            throw HttpRequestTimeoutException(request)
        }

        val result: ProviderOperationResult<ChangeSetAcknowledgement> = provider.pushChanges(
            PushChangesRequest(
                request = sampleSynchronizationRequest(direction = SynchronizationDirection.PUSH),
                changeSet = sampleChangeSet(changeSetId = "change-set-1", eventId = "event-1"),
            ),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<ClassifiedStrategyRemoteError>(failure.error)
        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(StrategyRemoteOutcome.TIMEOUT, error.remoteOutcome)
    }

    @Test
    fun `non 2xx responses are classified canonically`() = runTest {
        val provider: KtorTransportProvider = providerWithEngine { _ ->
            respond(
                content = "busy",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf("Retry-After", "3"),
            )
        }

        val result: ProviderOperationResult<ChangeSetAcknowledgement> = provider.pushChanges(
            PushChangesRequest(
                request = sampleSynchronizationRequest(direction = SynchronizationDirection.PUSH),
                changeSet = sampleChangeSet(changeSetId = "change-set-1", eventId = "event-1"),
            ),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(result)
        val error = assertIs<ClassifiedStrategyRemoteError>(failure.error)
        assertEquals(ErrorCategory.NETWORK, error.category)
        assertEquals(StrategyRemoteOutcome.RATE_LIMITED, error.remoteOutcome)
        val hintCarrier = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3000L, hintCarrier.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `other http status mappings remain canonical`() = runTest {
        val cases: List<HttpFailureCase> = listOf(
            HttpFailureCase(
                status = HttpStatusCode.Unauthorized,
                category = ErrorCategory.AUTHENTICATION,
                remoteOutcome = StrategyRemoteOutcome.AUTHENTICATION_FAILURE,
            ),
            HttpFailureCase(
                status = HttpStatusCode.Forbidden,
                category = ErrorCategory.AUTHORIZATION,
                remoteOutcome = StrategyRemoteOutcome.AUTHORIZATION_FAILURE,
            ),
            HttpFailureCase(
                status = HttpStatusCode.Conflict,
                category = ErrorCategory.CONFLICT,
                remoteOutcome = StrategyRemoteOutcome.CONFLICT,
            ),
            HttpFailureCase(
                status = HttpStatusCode.BadRequest,
                category = ErrorCategory.VALIDATION,
                remoteOutcome = StrategyRemoteOutcome.VALIDATION_FAILURE,
            ),
            HttpFailureCase(
                status = HttpStatusCode.BadGateway,
                category = ErrorCategory.NETWORK,
                remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
            ),
            HttpFailureCase(
                status = HttpStatusCode.InternalServerError,
                category = ErrorCategory.NETWORK,
                remoteOutcome = StrategyRemoteOutcome.SERVER_FAILURE,
            ),
        )

        cases.forEach { testCase ->
            val provider: KtorTransportProvider = providerWithEngine { _ ->
                respond(
                    content = "error",
                    status = testCase.status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                )
            }

            val result: ProviderOperationResult<ChangeSetAcknowledgement> = provider.pushChanges(
                PushChangesRequest(
                    request = sampleSynchronizationRequest(direction = SynchronizationDirection.PUSH),
                    changeSet = sampleChangeSet(changeSetId = "change-set-1", eventId = "event-1"),
                ),
            )

            val failure = assertIs<ProviderOperationResult.Failure>(result)
            val error = assertIs<ClassifiedStrategyRemoteError>(failure.error)
            assertEquals(testCase.category, error.category)
            assertEquals(testCase.remoteOutcome, error.remoteOutcome)
        }
    }

    @Test
    fun `encode failures return canonical serialization errors`() = runTest {
        val provider: KtorTransportProvider = providerWithCodec(
            codec = object : KtorTransportCodec by TestCodec {
                override suspend fun encodePushRequest(request: PushChangesRequest): KtorTransportHttpRequest {
                    throw IllegalStateException("encode failed")
                }
            },
        ) { _ ->
            respond(content = "unused", status = HttpStatusCode.OK)
        }

        val result: ProviderOperationResult<ChangeSetAcknowledgement> = provider.pushChanges(
            PushChangesRequest(
                request = sampleSynchronizationRequest(direction = SynchronizationDirection.PUSH),
                changeSet = sampleChangeSet(changeSetId = "change-set-1", eventId = "event-1"),
            ),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(ErrorCategory.SERIALIZATION, failure.error.category)
    }

    @Test
    fun `decode failures return canonical serialization errors`() = runTest {
        val provider: KtorTransportProvider = providerWithCodec(
            codec = object : KtorTransportCodec by TestCodec {
                override suspend fun decodePushResponse(
                    request: PushChangesRequest,
                    response: KtorTransportHttpResponse,
                ): ChangeSetAcknowledgement {
                    throw IllegalStateException("decode failed")
                }
            },
        ) { _ ->
            respond(content = "accepted:event-1", status = HttpStatusCode.OK)
        }

        val result: ProviderOperationResult<ChangeSetAcknowledgement> = provider.pushChanges(
            PushChangesRequest(
                request = sampleSynchronizationRequest(direction = SynchronizationDirection.PUSH),
                changeSet = sampleChangeSet(changeSetId = "change-set-1", eventId = "event-1"),
            ),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(ErrorCategory.SERIALIZATION, failure.error.category)
    }

    @Test
    fun `request and response diagnostics redact header values`() {
        val request: KtorTransportHttpRequest = KtorTransportHttpRequest(
            method = KtorTransportHttpMethod.POST,
            url = "https://example.test/push",
            headers = mapOf(HttpHeaders.Authorization to listOf("******")),
            body = "opaque-body".encodeToByteArray(),
            contentType = ContentType.Text.Plain.toString(),
        )
        val response: KtorTransportHttpResponse = KtorTransportHttpResponse(
            statusCode = 200,
            headers = mapOf(HttpHeaders.SetCookie to listOf("session=secret")),
            body = "opaque-response".encodeToByteArray(),
            contentType = ContentType.Text.Plain.toString(),
        )

        assertTrue(request.toString().contains(HttpHeaders.Authorization))
        assertFalse(request.toString().contains("******"))
        assertTrue(response.toString().contains(HttpHeaders.SetCookie))
        assertFalse(response.toString().contains("session=secret"))
    }

    private fun providerWithEngine(
        configureClient: HttpClientConfigBuilder = {},
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData,
    ): KtorTransportProvider = providerWithCodec(
        codec = TestCodec,
        configureClient = configureClient,
        handler = handler,
    )

    private fun providerWithCodec(
        codec: KtorTransportCodec,
        configureClient: HttpClientConfigBuilder = {},
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.HttpResponseData,
    ): KtorTransportProvider {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler(handler)
            }
            configureClient()
        }
        return KtorTransportProvider.createForTesting(
            codec = codec,
            descriptor = defaultTransportDescriptor(),
            httpClient = client,
            closeHttpClientOnClose = true,
        )
    }

    private fun sampleSynchronizationRequest(
        direction: SynchronizationDirection,
    ): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    )

    private fun sampleChangeSet(
        changeSetId: String,
        eventId: String,
    ): ChangeSet = ChangeSet(
        id = ChangeSetId(changeSetId),
        events = listOf(
            ChangeEvent(
                id = ChangeEventId(eventId),
                entity = EntityReference(
                    type = EntityType("widget"),
                    id = EntityId("widget-1"),
                ),
                operation = ChangeOperation.UPDATE,
                payload = DataLoomPayload(
                    contentType = PayloadContentType("application/octet-stream"),
                    bytes = "payload".encodeToByteArray(),
                ),
            ),
        ),
    )

    private fun sampleCheckpoint(token: String): SynchronizationCheckpoint = SynchronizationCheckpoint(
        key = CheckpointKey("widgets"),
        token = CheckpointToken(token),
    )
}

private object TestCodec : KtorTransportCodec {
    override suspend fun encodePushRequest(request: PushChangesRequest): KtorTransportHttpRequest =
        KtorTransportHttpRequest(
            method = KtorTransportHttpMethod.POST,
            url = "https://example.test/push",
            headers = mapOf(HttpHeaders.Authorization to listOf("******")),
            body = request.changeSet.id.value.encodeToByteArray(),
            contentType = ContentType.Text.Plain.toString(),
        )

    override suspend fun decodePushResponse(
        request: PushChangesRequest,
        response: KtorTransportHttpResponse,
    ): ChangeSetAcknowledgement {
        val eventId: String = response.copyBody().decodeToString().substringAfter(':')
        return ChangeSetAcknowledgement(
            changeSetId = request.changeSet.id,
            events = listOf(
                ChangeEventAcknowledgement(
                    eventId = ChangeEventId(eventId),
                    status = ChangeAcknowledgementStatus.ACCEPTED,
                ),
            ),
        )
    }

    override suspend fun encodePullRequest(request: PullChangesRequest): KtorTransportHttpRequest =
        KtorTransportHttpRequest(
            method = KtorTransportHttpMethod.POST,
            url = "https://example.test/pull",
            body = buildString {
                append(request.maxEvents ?: -1)
                append(':')
                append(request.checkpoint?.token?.value ?: "none")
            }.encodeToByteArray(),
            contentType = ContentType.Text.Plain.toString(),
        )

    override suspend fun decodePullResponse(
        request: PullChangesRequest,
        response: KtorTransportHttpResponse,
    ): PullChangesResult {
        val parts: List<String> = response.copyBody().decodeToString().split(':')
        return PullChangesResult.Changes(
            changeSet = ChangeSet(
                id = ChangeSetId("remote-change-set"),
                events = listOf(
                    ChangeEvent(
                        id = ChangeEventId(parts[3]),
                        entity = EntityReference(
                            type = EntityType("widget"),
                            id = EntityId("remote-entity-1"),
                        ),
                        operation = ChangeOperation.UPDATE,
                    ),
                ),
            ),
            hasMore = parts[1].toBooleanStrict(),
            nextCheckpoint = SynchronizationCheckpoint(
                key = CheckpointKey("widgets"),
                token = CheckpointToken(parts[2]),
            ),
        )
    }
}

private fun defaultTransportDescriptor() = io.dataloom.api.provider.ProviderDescriptor(
    id = io.dataloom.api.provider.ProviderId("io.dataloom.transport.ktor.test"),
    name = io.dataloom.api.provider.ProviderName("KtorTransportProviderTest"),
    type = io.dataloom.api.provider.ProviderType.TRANSPORT,
    version = io.dataloom.api.provider.ProviderVersion("1.0.0"),
)

private data class HttpFailureCase(
    val status: HttpStatusCode,
    val category: ErrorCategory,
    val remoteOutcome: StrategyRemoteOutcome,
)

private typealias HttpClientConfigBuilder = io.ktor.client.HttpClientConfig<io.ktor.client.engine.mock.MockEngineConfig>.() -> Unit
private typealias MockRequestHandleScope = io.ktor.client.engine.mock.MockRequestHandleScope
