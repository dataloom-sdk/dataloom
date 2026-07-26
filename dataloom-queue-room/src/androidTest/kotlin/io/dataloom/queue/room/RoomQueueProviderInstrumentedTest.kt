package io.dataloom.queue.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class RoomQueueProviderInstrumentedTest {

    private lateinit var context: Context
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var provider: RoomQueueProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            DataLoomRoomDatabase::class.java,
        ).build()
        provider = RoomQueueProvider(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun makeEntry(
        id: String,
        enqueuedAt: Long,
        availableAt: Long = enqueuedAt,
        metadata: DataLoomMetadata = DataLoomMetadata.Empty,
        contextMetadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId(id),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-$id"),
            sessionId = SynchronizationSessionId("session-$id"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-$id"),
                correlationId = CorrelationId("correlation-$id"),
                metadata = contextMetadata,
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(enqueuedAt),
        availableAt = DataLoomInstant(availableAt),
        metadata = metadata,
    )

    private suspend fun enqueue(entry: QueueEntry) {
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.enqueue(QueueEnqueueRequest(entry)),
        )
    }

    private suspend fun acquire(
        now: Long,
        limit: Int = 10,
        leaseId: String = "lease-$now",
        consumerId: String = "consumer-1",
        expiresAt: Long = now + 1_000L,
    ): QueueAcquireResult {
        val result = provider.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId(consumerId),
                leaseId = QueueLeaseId(leaseId),
                acquiredAt = DataLoomInstant(now),
                leaseExpiresAt = DataLoomInstant(expiresAt),
                maxEntries = limit,
            ),
        )
        return assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(result).value
    }

    @Test
    fun enqueueAcquireCompleteAndStaleLeaseArePersistedTransactionally() = runBlocking {
        enqueue(makeEntry("entry-1", enqueuedAt = 1_000L))

        val acquired = assertIs<QueueAcquireResult.Entries>(
            acquire(now = 1_000L, leaseId = "lease-1"),
        )
        assertEquals(listOf("entry-1"), acquired.entries.map { it.id.value })
        assertEquals("lease-1", acquired.entries.single().lease?.id?.value)

        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.complete(
                QueueCompletionRequest(
                    entryId = QueueEntryId("entry-1"),
                    leaseId = QueueLeaseId("lease-1"),
                    completedAt = DataLoomInstant(1_500L),
                ),
            ),
        )

        val staleResult = provider.complete(
            QueueCompletionRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("lease-1"),
                completedAt = DataLoomInstant(1_600L),
            ),
        )
        val staleFailure = assertIs<ProviderOperationResult.Failure>(staleResult)
        assertEquals("QUEUE_STALE_LEASE", staleFailure.error.code.value)
        assertIs<QueueAcquireResult.NoEntries>(acquire(now = 2_000L))
    }

    @Test
    fun acquisitionIsBoundedOrderedAndExcludesFutureEntries() = runBlocking {
        enqueue(makeEntry("third", enqueuedAt = 3_000L, availableAt = 3_000L))
        enqueue(makeEntry("first", enqueuedAt = 1_000L, availableAt = 1_000L))
        enqueue(makeEntry("future", enqueuedAt = 2_000L, availableAt = 9_000L))
        enqueue(makeEntry("second", enqueuedAt = 2_000L, availableAt = 2_000L))

        val firstBatch = assertIs<QueueAcquireResult.Entries>(
            acquire(now = 4_000L, limit = 2, leaseId = "lease-batch-1"),
        )
        assertEquals(listOf("first", "second"), firstBatch.entries.map { it.id.value })

        val secondBatch = assertIs<QueueAcquireResult.Entries>(
            acquire(now = 4_000L, limit = 2, leaseId = "lease-batch-2"),
        )
        assertEquals(listOf("third"), secondBatch.entries.map { it.id.value })
    }

    @Test
    fun concurrentConsumersDoNotAcquireTheSameEntry() = runBlocking {
        enqueue(makeEntry("single", enqueuedAt = 1_000L))

        val results = coroutineScope {
            listOf(
                async { acquire(1_000L, leaseId = "lease-a", consumerId = "consumer-a") },
                async { acquire(1_000L, leaseId = "lease-b", consumerId = "consumer-b") },
            ).map { it.await() }
        }

        val acquiredIds = results
            .filterIsInstance<QueueAcquireResult.Entries>()
            .flatMap { it.entries }
            .map { it.id.value }
        assertEquals(listOf("single"), acquiredIds)
    }

    @Test
    fun expiredLeaseIsRecoveredButActiveLeaseIsPreserved() = runBlocking {
        enqueue(makeEntry("expired", enqueuedAt = 1_000L))
        enqueue(makeEntry("active", enqueuedAt = 1_001L))

        assertIs<QueueAcquireResult.Entries>(
            acquire(1_100L, limit = 1, leaseId = "expired-lease", expiresAt = 2_000L),
        )
        assertIs<QueueAcquireResult.Entries>(
            acquire(1_100L, limit = 1, leaseId = "active-lease", expiresAt = 5_000L),
        )

        val recovery = provider.recoverExpiredLeases(
            ExpiredLeaseRecoveryRequest(currentTime = DataLoomInstant(3_000L)),
        )
        assertEquals(
            1,
            assertIs<ProviderOperationResult.Success<io.dataloom.api.queue.ExpiredLeaseRecoveryResult>>(
                recovery,
            ).value.recoveredEntries,
        )

        val reacquired = assertIs<QueueAcquireResult.Entries>(
            acquire(3_000L, leaseId = "recovered-lease"),
        )
        assertEquals(listOf("expired"), reacquired.entries.map { it.id.value })
    }

    @Test
    fun nonEmptyMetadataRoundTripsUsingAndroidJsonImplementation() = runBlocking {
        val entryMetadata = DataLoomMetadata.of(mapOf("entry-key" to "entry-value"))
        val contextMetadata = DataLoomMetadata.of(mapOf("context-key" to "context-value"))
        enqueue(
            makeEntry(
                id = "metadata",
                enqueuedAt = 1_000L,
                metadata = entryMetadata,
                contextMetadata = contextMetadata,
            ),
        )

        val acquired = assertIs<QueueAcquireResult.Entries>(
            acquire(1_000L, leaseId = "metadata-lease"),
        ).entries.single()
        assertEquals(entryMetadata, acquired.metadata)
        assertEquals(contextMetadata, acquired.synchronizationRequest.context.metadata)
    }

    @Test
    fun queueSurvivesDatabaseCloseAndReopen() = runBlocking {
        val databaseName = "dataloom-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val firstDatabase = DataLoomDatabaseBuilder.build(context, databaseName)
        val firstProvider = RoomQueueProvider(firstDatabase)
        try {
            assertIs<ProviderOperationResult.Success<Unit>>(
                firstProvider.enqueue(
                    QueueEnqueueRequest(makeEntry("persisted", enqueuedAt = 1_000L)),
                ),
            )
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = DataLoomDatabaseBuilder.build(context, databaseName)
        try {
            val reopenedProvider = RoomQueueProvider(reopenedDatabase)
            val result = reopenedProvider.acquire(
                QueueAcquireRequest(
                    consumerId = QueueConsumerId("reopen-consumer"),
                    leaseId = QueueLeaseId("reopen-lease"),
                    acquiredAt = DataLoomInstant(2_000L),
                    leaseExpiresAt = DataLoomInstant(3_000L),
                    maxEntries = 1,
                ),
            )
            val entries = assertIs<QueueAcquireResult.Entries>(
                assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(result).value,
            ).entries
            assertEquals("persisted", entries.single().id.value)
        } finally {
            reopenedDatabase.close()
            assertTrue(context.deleteDatabase(databaseName))
        }
    }
}
