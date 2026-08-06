package io.dataloom.queue.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueIdempotentAdmissionResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomQueueIdempotentAdmissionInstrumentedTest {

    private lateinit var database: DataLoomRoomDatabase
    private lateinit var provider: RoomQueueProvider

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
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

    @Test
    fun firstAndRepeatedAdmissionAreTypedAndDoNotDuplicateRows() = runBlocking {
        val first = provider.admit(QueueEnqueueRequest(entry())).successValue()
        val repeated = provider.admit(
            QueueEnqueueRequest(entry(enqueuedAt = 2_000L, availableAt = 3_000L)),
        ).successValue()

        assertIs<QueueIdempotentAdmissionResult.Accepted>(first)
        val already = assertIs<QueueIdempotentAdmissionResult.AlreadyAccepted>(repeated)
        assertEquals(QueueEntryState.PENDING, already.currentState)
    }

    @Test
    fun sameIdForDifferentImmutableWorkReturnsIdentityConflict() = runBlocking {
        provider.admit(QueueEnqueueRequest(entry())).successValue()

        val conflict = provider.admit(
            QueueEnqueueRequest(entry(workflowId = "other-workflow")),
        ).successValue()

        val typed = assertIs<QueueIdempotentAdmissionResult.IdentityConflict>(conflict)
        assertEquals(QueueEntryState.PENDING, typed.currentState)
    }

    @Test
    fun concurrentProvidersProduceOneAcceptedAndOneAlreadyAccepted() = runBlocking {
        val firstProvider = RoomQueueProvider(database)
        val secondProvider = RoomQueueProvider(database)

        val results = coroutineScope {
            listOf(
                async { firstProvider.admit(QueueEnqueueRequest(entry())) },
                async { secondProvider.admit(QueueEnqueueRequest(entry())) },
            ).awaitAll().map { it.successValue() }
        }

        assertEquals(1, results.count { it is QueueIdempotentAdmissionResult.Accepted })
        assertEquals(1, results.count { it is QueueIdempotentAdmissionResult.AlreadyAccepted })
    }

    private fun entry(
        workflowId: String = "room-idempotent-workflow",
        enqueuedAt: Long = 1_000L,
        availableAt: Long = enqueuedAt,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("room-stable-admission"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId(workflowId),
            sessionId = SynchronizationSessionId("room-idempotent-session"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("room-idempotent-execution"),
                correlationId = CorrelationId("room-idempotent-correlation"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(enqueuedAt),
        availableAt = DataLoomInstant(availableAt),
    )

    private fun <T> ProviderOperationResult<T>.successValue(): T =
        assertIs<ProviderOperationResult.Success<T>>(this).value
}
