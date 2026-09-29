package io.dataloom.transport.ktor

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
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
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers

/** Sample request shared by the Retry-After tests. */
internal val retryAfterPushRequest: PushChangesRequest = PushChangesRequest(
    request = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    ),
    changeSet = ChangeSet(
        id = ChangeSetId("change-set-1"),
        events = listOf(
            ChangeEvent(
                id = ChangeEventId("event-1"),
                entity = EntityReference(type = EntityType("widget"), id = EntityId("widget-1")),
                operation = ChangeOperation.UPDATE,
            ),
        ),
    ),
)

private object UnusedDecodeCodec : KtorTransportCodec {
    override suspend fun encodePushRequest(request: PushChangesRequest): KtorTransportHttpRequest =
        KtorTransportHttpRequest(
            method = KtorTransportHttpMethod.POST,
            url = "https://example.test/push",
            contentType = ContentType.Text.Plain.toString(),
        )

    override suspend fun decodePushResponse(
        request: PushChangesRequest,
        response: KtorTransportHttpResponse,
    ): ChangeSetAcknowledgement = error("The failure path must not decode a response.")

    override suspend fun encodePullRequest(request: PullChangesRequest): KtorTransportHttpRequest =
        error("Not used.")

    override suspend fun decodePullResponse(
        request: PullChangesRequest,
        response: KtorTransportHttpResponse,
    ): PullChangesResult = error("Not used.")
}

/**
 * Pushes through a real [KtorTransportProvider] whose mock server answers with
 * [status] and the given raw [headerLines] (name to value, repeated names are
 * repeated header lines) and returns the produced error.
 */
internal suspend fun retryAfterFailure(
    status: Int,
    headerLines: List<Pair<String, String>>,
    clock: DataLoomClock?,
): DataLoomError {
    val client = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond(
                    content = "busy",
                    status = HttpStatusCode.fromValue(status),
                    headers = headers {
                        headerLines.forEach { (name, value) -> append(name, value) }
                    },
                )
            }
        }
    }
    val provider: KtorTransportProvider = KtorTransportProvider.createForTesting(
        codec = UnusedDecodeCodec,
        descriptor = ProviderDescriptor(
            id = ProviderId("io.dataloom.transport.ktor.retry-after-test"),
            name = ProviderName("KtorRetryAfterTest"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        ),
        httpClient = client,
        closeHttpClientOnClose = true,
        clock = clock,
    )
    val result: ProviderOperationResult<ChangeSetAcknowledgement> =
        provider.pushChanges(retryAfterPushRequest)
    provider.close()
    return (result as ProviderOperationResult.Failure).error
}
