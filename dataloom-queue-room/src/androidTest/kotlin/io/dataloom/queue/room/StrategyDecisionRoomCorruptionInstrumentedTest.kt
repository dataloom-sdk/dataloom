package io.dataloom.queue.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class StrategyDecisionRoomCorruptionInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun partialStrategyDecisionFailsBeforeAnEntryIsAcquired() = runBlocking {
        val database = Room.databaseBuilder(
            context,
            DataLoomRoomDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(*DataLoomRoomMigrations.ALL)
            .build()
        try {
            val provider = RoomQueueProvider(database)
            assertIs<ProviderOperationResult.Success<Unit>>(
                provider.enqueue(QueueEnqueueRequest(entry())),
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE queue_entries SET strategy_plan_id = NULL " +
                    "WHERE entry_id = 'corrupt-strategy-entry'",
            )

            val failure = assertIs<ProviderOperationResult.Failure>(
                provider.acquire(
                    QueueAcquireRequest(
                        consumerId = QueueConsumerId("consumer-1"),
                        leaseId = QueueLeaseId("lease-1"),
                        acquiredAt = DataLoomInstant(2_000L),
                        leaseExpiresAt = DataLoomInstant(3_000L),
                        maxEntries = 1,
                    ),
                ),
            )

            assertEquals("QUEUE_DATABASE_FAILURE", failure.error.code.value)
            assertNull(failure.error.cause)
        } finally {
            database.close()
        }
    }

    private fun entry(): QueueEntry = QueueEntry(
        id = QueueEntryId("corrupt-strategy-entry"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        strategyDecision = PersistedStrategyDecision(
            decisionId = StrategyDecisionId("decision-1"),
            planId = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId("offline-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(2L),
            disposition = StrategyDisposition.DEFER,
        ),
    )

    private companion object {
        const val DATABASE_NAME = "dataloom-strategy-decision-corruption-test"
    }
}
