package io.dataloom.transport.retrofit

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.RetryAfterParser
import io.dataloom.api.error.RetryDelayHintCarrier
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
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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

/**
 * Table-driven coverage of the Retrofit adapter's `Retry-After` handling,
 * driving a real OkHttp/Retrofit stack against [MockWebServer] so the produced
 * [retrofit2.HttpException] carries a genuine [okhttp3.Headers] instance —
 * proving the adapter reads real transport data, not a synthesized header.
 */
class RetrofitRetryAfterTest {
    private class FixedClock(private val epochMilliseconds: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(epochMilliseconds)
    }

    @Test
    fun `delta seconds`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "3"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `http date in the future`() = runTest {
        val error = pushFailure(
            503,
            listOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"),
            clock = FixedClock(784_111_777_000L - 30_000L),
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(30_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `http date in the past clamps to zero`() = runTest {
        val error = pushFailure(
            429,
            listOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"),
            clock = FixedClock(784_111_777_000L + 60_000L),
        )
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(0L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `http date without a configured clock produces no hint`() = runTest {
        val error = pushFailure(
            429,
            listOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"),
            clock = null,
        )
        assertNull(error as? RetryDelayHintCarrier)
        assertEquals("RETROFIT_HTTP_429", error.code.value)
    }

    @Test
    fun `malformed value produces no hint but status still classifies`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "not-a-delay"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
        assertEquals("RETROFIT_HTTP_429", error.code.value)
    }

    @Test
    fun `negative value produces no hint`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "-5"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
    }

    @Test
    fun `huge value clamps to the maximum delay`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "99999999999999999999999"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(RetryAfterParser.MAXIMUM_DELAY_MILLISECONDS, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `missing header produces no hint`() = runTest {
        val error = pushFailure(429, emptyList(), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
    }

    @Test
    fun `multiple headers use the first value`() = runTest {
        val error = pushFailure(429, listOf("Retry-After" to "3", "Retry-After" to "300"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `header name is case insensitive`() = runTest {
        val error = pushFailure(429, listOf("retry-after" to "3"), clock = null)
        val hint = assertIs<RetryDelayHintCarrier>(error)
        assertEquals(3_000L, hint.retryDelayHint.delayMilliseconds)
    }

    @Test
    fun `503 without Retry-After produces no hint`() = runTest {
        val error = pushFailure(503, emptyList(), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
        assertEquals("RETROFIT_HTTP_503", error.code.value)
    }

    @Test
    fun `500 never produces a hint even with the header present`() = runTest {
        val error = pushFailure(500, listOf("Retry-After" to "3"), clock = null)
        assertNull(error as? RetryDelayHintCarrier)
        assertEquals("RETROFIT_HTTP_500", error.code.value)
    }

    private suspend fun pushFailure(
        statusCode: Int,
        headerLines: List<Pair<String, String>>,
        clock: DataLoomClock?,
    ): DataLoomError {
        MockWebServer().use { server ->
            var response = MockResponse().setResponseCode(statusCode).setBody("failure")
            headerLines.forEach { (name, value) -> response = response.addHeader(name, value) }
            server.enqueue(response)

            val retrofit = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(OkHttpClient.Builder().build())
                .build()
            val service = retrofit.create(TestService::class.java)

            val provider = RetrofitTransportProvider<RequestBody, ResponseBody, RequestBody, ResponseBody>(
                descriptor = ProviderDescriptor(
                    id = ProviderId("provider.transport.retrofit.retry-after-test"),
                    name = ProviderName("Retrofit Retry-After Test Provider"),
                    type = ProviderType.TRANSPORT,
                    version = ProviderVersion("1.0.0"),
                ),
                pushRequestMapper = { "push".toRequestBody("text/plain".toMediaType()) },
                pullRequestMapper = { "pull".toRequestBody("text/plain".toMediaType()) },
                pushCall = service::push,
                pullCall = service::pull,
                pushResponseMapper = { _, _ -> error("unreachable: response was a failure") },
                pullResponseMapper = { _, _ -> PullChangesResult.NoChanges() },
                clock = clock,
            )

            val result = provider.pushChanges(pushRequest())
            return (result as ProviderOperationResult.Failure).error
        }
    }

    private fun pushRequest(): PushChangesRequest = PushChangesRequest(
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

    private interface TestService {
        @POST("push")
        suspend fun push(@Body body: RequestBody): ResponseBody

        @POST("pull")
        suspend fun pull(@Body body: RequestBody): ResponseBody
    }
}
