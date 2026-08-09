package io.dataloom.transport.retrofit

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
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
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST

class RetrofitTransportProviderTest {

    @Test
    fun `push success returns acknowledgement from retrofit response`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("accepted"))
            val provider = createProvider(server)

            val request = pushRequest()
            val result = provider.pushChanges(request)

            val success = assertIs<ProviderOperationResult.Success<ChangeSetAcknowledgement>>(result)
            assertEquals(request.changeSet.id, success.value.changeSetId)
            assertEquals(request.changeSet.events.size, success.value.events.size)
            assertEquals(ChangeAcknowledgementStatus.ACCEPTED, success.value.events.first().status)
            assertEquals("push:cs-001", server.takeRequest().body.readUtf8())
        }
    }

    @Test
    fun `pull success can return changes with hasMore and next checkpoint`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("changes|true|next-token"))
            val provider = createProvider(server)

            val result = provider.pullChanges(pullRequest(maxEvents = 25))

            val success = assertIs<ProviderOperationResult.Success<PullChangesResult>>(result)
            val changes = assertIs<PullChangesResult.Changes>(success.value)
            assertEquals(true, changes.hasMore)
            assertEquals("next-token", changes.nextCheckpoint?.token?.value)
            assertEquals("inbound-001", changes.changeSet.id.value)
            assertEquals("pull:25", server.takeRequest().body.readUtf8())
        }
    }

    @Test
    fun `timeout failures map to canonical dataloom error`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeadersDelay(1, TimeUnit.SECONDS)
                    .setBody("accepted"),
            )
            val provider = createProvider(server, callTimeoutMillis = 100)

            val result = provider.pushChanges(pushRequest())

            val failure = assertIs<ProviderOperationResult.Failure>(result)
            assertEquals("RETROFIT_TIMEOUT", failure.error.code.value)
            assertEquals(io.dataloom.api.error.ErrorCategory.NETWORK, failure.error.category)
        }
    }

    @Test
    fun `non-2xx retrofit responses map to canonical dataloom error`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
            val provider = createProvider(server)

            val result = provider.pullChanges(pullRequest())

            val failure = assertIs<ProviderOperationResult.Failure>(result)
            assertEquals("RETROFIT_HTTP_401", failure.error.code.value)
            assertEquals(io.dataloom.api.error.ErrorCategory.AUTHENTICATION, failure.error.category)
        }
    }

    private fun createProvider(
        server: MockWebServer,
        callTimeoutMillis: Long = 2_000,
    ): RetrofitTransportProvider<RequestBody, ResponseBody, RequestBody, ResponseBody> {
        val client = OkHttpClient.Builder()
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .build()

        val service = retrofit.create(TestService::class.java)

        return RetrofitTransportProvider(
            descriptor = ProviderDescriptor(
                id = ProviderId("provider.transport.retrofit.test"),
                name = ProviderName("Retrofit Test Provider"),
                type = ProviderType.TRANSPORT,
                version = ProviderVersion("1.0.0"),
            ),
            pushRequestMapper = { request ->
                "push:${request.changeSet.id.value}".toRequestBody("text/plain".toMediaType())
            },
            pullRequestMapper = { request ->
                "pull:${request.maxEvents ?: "all"}".toRequestBody("text/plain".toMediaType())
            },
            pushCall = service::push,
            pullCall = service::pull,
            pushResponseMapper = { request, responseBody ->
                responseBody.use { body ->
                    check(body.string() == "accepted")
                }
                ChangeSetAcknowledgement(
                    changeSetId = request.changeSet.id,
                    events = request.changeSet.events.map { event ->
                        ChangeEventAcknowledgement(
                            eventId = event.id,
                            status = ChangeAcknowledgementStatus.ACCEPTED,
                        )
                    },
                )
            },
            pullResponseMapper = { _, responseBody ->
                val tokens = responseBody.use { it.string() }.split('|')
                when (tokens.firstOrNull()) {
                    "changes" -> PullChangesResult.Changes(
                        changeSet = inboundChangeSet(),
                        hasMore = tokens.getOrNull(1)?.toBooleanStrictOrNull() ?: false,
                        nextCheckpoint = tokens.getOrNull(2)?.let(::checkpoint),
                    )

                    else -> PullChangesResult.NoChanges(nextCheckpoint = tokens.getOrNull(1)?.let(::checkpoint))
                }
            },
        )
    }

    private fun pushRequest(): PushChangesRequest = PushChangesRequest(
        request = synchronizationRequest(),
        changeSet = ChangeSet(
            id = ChangeSetId("cs-001"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("event-001"),
                    entity = EntityReference(
                        type = EntityType("Contact"),
                        id = EntityId("contact-001"),
                    ),
                    operation = ChangeOperation.UPDATE,
                ),
            ),
        ),
    )

    private fun pullRequest(maxEvents: Int? = null): PullChangesRequest = PullChangesRequest(
        request = synchronizationRequest(),
        maxEvents = maxEvents,
        checkpoint = checkpoint("cursor-001"),
    )

    private fun inboundChangeSet(): ChangeSet = ChangeSet(
        id = ChangeSetId("inbound-001"),
        events = listOf(
            ChangeEvent(
                id = ChangeEventId("event-inbound-001"),
                entity = EntityReference(type = EntityType("Contact"), id = EntityId("contact-001")),
                operation = ChangeOperation.CREATE,
            ),
        ),
    )

    private fun checkpoint(token: String): SynchronizationCheckpoint = SynchronizationCheckpoint(
        key = CheckpointKey("contacts"),
        token = CheckpointToken(token),
    )

    private fun synchronizationRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("contacts"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("exec-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private interface TestService {
        @POST("push")
        suspend fun push(@Body body: RequestBody): ResponseBody

        @POST("pull")
        suspend fun pull(@Body body: RequestBody): ResponseBody
    }
}
