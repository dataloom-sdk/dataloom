package io.dataloom.runtime.state

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventOutboxBatchReplayRequest
import io.dataloom.api.operational.OperationalEventOutboxEntryPage
import io.dataloom.api.operational.OperationalEventOutboxEntryQuery
import io.dataloom.api.operational.OperationalEventOutboxEntryStatus
import io.dataloom.api.operational.OperationalEventOutboxReplayAuthorizer
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.operational.OperationalEventOutboxStateCodec
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataClassification
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Cross-scope enumeration and authorizer-gated batch replay of
 * [DurableOperationalEventOutbox] over the real [AppleFileDurableStateStore]:
 * the replayed state must be what a brand-new store instance on the same file
 * (the process-restart shape) reads back. Uses `runBlocking`, not `runTest`,
 * because the store's lock retry uses real `delay`.
 */
class AppleFileDurableOperationalEventOutboxEnumerationTest {

    private val alpha = OperationalEventOutboxScope("alpha")
    private val beta = OperationalEventOutboxScope("beta")
    private val clock = object : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(1_000L)
    }

    @Test
    fun batchReplayAndEnumerationSurviveARestartOnTheRealFileStore(): Unit = runBlocking {
        val directory = uniqueDirectory()
        val outbox = outboxOn(directory)
        outbox.append(alpha, envelope("a1", "w1"))
        outbox.append(alpha, envelope("a2", "w2"))
        outbox.append(beta, envelope("b1", "w1"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        outbox.acknowledge(alpha, OperationalEventId("a2"))
        outbox.acknowledge(beta, OperationalEventId("b1"))

        val denied = outbox.replayBatch(
            OperationalEventOutboxBatchReplayRequest(listOf(alpha, beta), workflowId = WorkflowId("w1")),
            OperationalEventOutboxReplayAuthorizer { _, _ -> false },
        )
        assertEquals(0, denied.replayed.size)
        assertEquals(2, denied.denied)

        val replayed = outbox.replayBatch(
            OperationalEventOutboxBatchReplayRequest(listOf(alpha, beta), workflowId = WorkflowId("w1")),
            OperationalEventOutboxReplayAuthorizer { _, _ -> true },
        )
        assertEquals(listOf("a1", "b1"), replayed.replayed.map { it.entry.envelope.id.value })

        // A brand-new store and outbox instance on the same file sees exactly that.
        val reopened = outboxOn(directory)
        val query = OperationalEventOutboxEntryQuery(listOf(beta, alpha), pageSize = 2)
        val first = page(reopened, query)
        assertEquals(listOf("a1", "a2"), first.entries.map { it.entry.envelope.id.value })
        assertEquals(listOf(false, true), first.entries.map { it.entry.isAcknowledged })
        val second = assertIs<ProviderOperationResult.Success<OperationalEventOutboxEntryPage>>(
            reopened.enumerate(query, first.nextCursor),
        ).value
        assertEquals(listOf("b1"), second.entries.map { it.entry.envelope.id.value })
        assertNull(second.nextCursor)

        val acknowledgedOnly = page(
            reopened,
            OperationalEventOutboxEntryQuery(listOf(alpha, beta), status = OperationalEventOutboxEntryStatus.ACKNOWLEDGED),
        )
        assertEquals(listOf("a2"), acknowledgedOnly.entries.map { it.entry.envelope.id.value })
    }

    private suspend fun page(outbox: DurableOperationalEventOutbox, query: OperationalEventOutboxEntryQuery): OperationalEventOutboxEntryPage =
        assertIs<ProviderOperationResult.Success<OperationalEventOutboxEntryPage>>(outbox.enumerate(query)).value

    private fun outboxOn(directory: String): DurableOperationalEventOutbox = DurableOperationalEventOutbox(
        store = AppleFileDurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>(
            directoryPath = directory,
            fileName = "operational-event-outbox.tsv",
            scopeKeyEncoder = OperationalEventOutboxScope.KeyEncoder,
            codec = OperationalEventOutboxStateCodec(),
        ),
        clock = clock,
    )

    private fun uniqueDirectory(): String =
        NSTemporaryDirectory().trimEnd('/') + "/dataloom-apple-outbox-enumeration-" + NSUUID().UUIDString

    private fun envelope(id: String, workflow: String): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = OperationalEventType("dataloom.retry.scheduled"),
        source = OperationalEventSource("dataloom.runtime.retry"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(1_000L),
        correlationId = CorrelationId("correlation-1"),
        workflowId = WorkflowId(workflow),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.retry.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )
}
