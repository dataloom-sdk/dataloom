package io.dataloom.queue.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
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

@RunWith(AndroidJUnit4::class)
class StrategyDecisionRoomPersistenceInstrumentedTest {

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
    fun strategyDecisionSurvivesReopenRetryDeferralAndLeaseRecovery() = runBlocking {
        val expected = decision()
        val initialDatabase = openDatabase()
        try {
            assertIs<ProviderOperationResult.Success<Unit>>(
                RoomQueueProvider(initialDatabase).enqueue(
                    QueueEnqueueRequest(entry(expected)),
                ),
            )
        } finally {
            initialDatabase.close()
        }

        val reopenedDatabase = openDatabase()
        try {
            val provider = RoomQueueProvider(reopenedDatabase)
            val first = acquired(provider, 2_000L, "lease-1")
            assertEquals(expected, first.strategyDecision)
            assertIs<ProviderOperationResult.Success<Unit>>(
                provider.reschedule(
                    QueueRescheduleRequest(
                        entryId = first.id,
                        leaseId = requireNotNull(first.lease).id,
                        retryAttempt = RetryAttempt(1),
                        availableAt = DataLoomInstant(3_000L),
                        error = TestError(),
                    ),
                ),
            )

            val retried = acquired(provider, 3_000L, "lease-2")
            assertEquals(expected, retried.strategyDecision)
            assertIs<ProviderOperationResult.Success<Unit>>(
                provider.defer(
                    QueueDeferralRequest(
                        entryId = retried.id,
                        leaseId = requireNotNull(retried.lease).id,
                        availableAt = DataLoomInstant(4_000L),
                        reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                    ),
                ),
            )

            val deferred = acquired(provider, 4_000L, "lease-3", expiresAt = 5_000L)
            assertEquals(expected, deferred.strategyDecision)
            assertIs<ProviderOperationResult.Success<io.dataloom.api.queue.ExpiredLeaseRecoveryResult>>(
                provider.recoverExpiredLeases(
                    ExpiredLeaseRecoveryRequest(DataLoomInstant(5_001L)),
                ),
            )
            assertEquals(expected, acquired(provider, 6_000L, "lease-4").strategyDecision)
        } finally {
            reopenedDatabase.close()
        }
    }

    private fun openDatabase(): DataLoomRoomDatabase = Room.databaseBuilder(
        context,
        DataLoomRoomDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(*DataLoomRoomMigrations.ALL)
        .build()

    private suspend fun acquired(
        provider: RoomQueueProvider,
        now: Long,
        leaseId: String,
        expiresAt: Long = now + 1_000L,
    ): QueueEntry {
        val result = provider.acquire(
            QueueAcquireRequest(
                consumerId = QueueConsumerId("consumer-1"),
                leaseId = QueueLeaseId(leaseId),
                acquiredAt = DataLoomInstant(now),
                leaseExpiresAt = DataLoomInstant(expiresAt),
                maxEntries = 1,
            ),
        )
        return assertIs<QueueAcquireResult.Entries>(
            assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(result).value,
        ).entries.single()
    }

    private fun entry(decision: PersistedStrategyDecision): QueueEntry = QueueEntry(
        id = QueueEntryId("strategy-entry"),
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
        strategyDecision = decision,
    )

    private fun decision(): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(12L),
        disposition = StrategyDisposition.DEFER,
    )

    private data class TestError(
        override val code: ErrorCode = ErrorCode("NETWORK_RETRY"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Retry later.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        const val DATABASE_NAME = "dataloom-strategy-decision-persistence-test"
    }
}
