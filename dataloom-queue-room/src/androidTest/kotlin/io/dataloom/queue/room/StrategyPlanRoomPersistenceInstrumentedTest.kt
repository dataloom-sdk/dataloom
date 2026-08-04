package io.dataloom.queue.room

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrategyPlanRoomPersistenceInstrumentedTest {

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DataLoomRoomDatabase::class.java,
    )

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        context.deleteDatabase(CORRUPTION_DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        context.deleteDatabase(CORRUPTION_DATABASE_NAME)
    }

    @Test
    fun versionSevenDecisionMigratesWithoutInventingCurrentPlan() {
        val versionSeven = migrationTestHelper.createDatabase(MIGRATION_DATABASE_NAME, 7)
        versionSeven.execSQL(
            """
            INSERT INTO queue_entries (
                entry_id, workflow_id, session_id, direction, mode, priority,
                exec_execution_id, exec_correlation_id, state,
                enqueued_at_ms, available_at_ms,
                strategy_decision_id, strategy_plan_id,
                strategy_requested_strategy, strategy_effective_profile_id,
                strategy_effective_strategy, strategy_configuration_version,
                strategy_disposition
            ) VALUES (
                'legacy-plan-entry', 'workflow-legacy', 'session-legacy',
                'PUSH', 'DELTA', 'NORMAL',
                'execution-legacy', 'correlation-legacy', 'PENDING',
                1000, 1000,
                'decision-legacy', 'plan-legacy',
                'ADAPTIVE', 'offline-profile',
                'OFFLINE_FIRST', 7, 'DEFER'
            )
            """.trimIndent(),
        )
        versionSeven.close()

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            MIGRATION_DATABASE_NAME,
            8,
            true,
            DataLoomRoomMigrations.MIGRATION_7_8,
        )
        val cursor = migrated.query(
            """
            SELECT strategy_decision_id, strategy_plan_id, strategy_plan_snapshot
            FROM queue_entries WHERE entry_id = 'legacy-plan-entry'
            """.trimIndent(),
        )
        try {
            assertTrue(cursor.moveToFirst())
            assertEquals("decision-legacy", cursor.getString(0))
            assertEquals("plan-legacy", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        } finally {
            cursor.close()
            migrated.close()
        }
    }

    @Test
    fun exactPlanSurvivesReopenRetryDeferralAndExpiredLeaseRecovery() = runBlocking {
        val expectedDecision = decision()
        val expectedPlan = plan()
        openProvider(DATABASE_NAME).use { opened ->
            opened.provider.enqueue(
                QueueEnqueueRequest(entry(expectedDecision, expectedPlan)),
            ).assertSuccess()
        }

        val first = openProvider(DATABASE_NAME).use { opened ->
            opened.provider.acquireEntry(2_000L, 3_000L, "lease-1")
        }
        assertEquals(expectedPlan, first.strategyPlan)

        openProvider(DATABASE_NAME).use { opened ->
            opened.provider.reschedule(
                QueueRescheduleRequest(
                    entryId = first.id,
                    leaseId = requireNotNull(first.lease).id,
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(4_000L),
                    error = TestError(),
                ),
            ).assertSuccess()
        }

        val retried = openProvider(DATABASE_NAME).use { opened ->
            opened.provider.acquireEntry(4_000L, 5_000L, "lease-2")
        }
        assertEquals(expectedPlan, retried.strategyPlan)

        openProvider(DATABASE_NAME).use { opened ->
            opened.provider.defer(
                QueueDeferralRequest(
                    entryId = retried.id,
                    leaseId = requireNotNull(retried.lease).id,
                    availableAt = DataLoomInstant(6_000L),
                    reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                ),
            ).assertSuccess()
        }

        val deferred = openProvider(DATABASE_NAME).use { opened ->
            opened.provider.acquireEntry(6_000L, 7_000L, "lease-3")
        }
        assertEquals(expectedPlan, deferred.strategyPlan)

        openProvider(DATABASE_NAME).use { opened ->
            assertIs<ProviderOperationResult.Success<io.dataloom.api.queue.ExpiredLeaseRecoveryResult>>(
                opened.provider.recoverExpiredLeases(
                    ExpiredLeaseRecoveryRequest(DataLoomInstant(7_001L)),
                ),
            )
        }

        val recovered = openProvider(DATABASE_NAME).use { opened ->
            opened.provider.acquireEntry(8_000L, 9_000L, "lease-4")
        }
        assertEquals(expectedDecision, recovered.strategyDecision)
        assertEquals(expectedPlan, recovered.strategyPlan)
    }

    @Test
    fun malformedPlanSnapshotFailsClosedBeforeAcquisition() = runBlocking {
        val opened = openProvider(CORRUPTION_DATABASE_NAME)
        try {
            opened.provider.enqueue(QueueEnqueueRequest(entry(decision(), plan()))).assertSuccess()
            opened.database.openHelper.writableDatabase.execSQL(
                "UPDATE queue_entries SET strategy_plan_snapshot = 'malformed-plan' " +
                    "WHERE entry_id = 'entry-plan'",
            )
            val failure = assertIs<ProviderOperationResult.Failure>(
                opened.provider.acquire(acquireRequest(2_000L, 3_000L, "lease-corrupt")),
            )
            assertEquals("QUEUE_DATABASE_FAILURE", failure.error.code.value)
            assertNull(failure.error.cause)
            assertTrue("malformed-plan" !in failure.error.message)
        } finally {
            opened.close()
        }
    }

    private fun openProvider(name: String): OpenedProvider {
        val database = Room.databaseBuilder(context, DataLoomRoomDatabase::class.java, name)
            .addMigrations(*DataLoomRoomMigrations.ALL)
            .build()
        return OpenedProvider(database, RoomQueueProvider(database))
    }

    private suspend fun RoomQueueProvider.acquireEntry(
        acquiredAt: Long,
        expiresAt: Long,
        leaseId: String,
    ): QueueEntry {
        val result = assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(
            acquire(acquireRequest(acquiredAt, expiresAt, leaseId)),
        ).value
        return assertIs<QueueAcquireResult.Entries>(result).entries.single()
    }

    private fun acquireRequest(
        acquiredAt: Long,
        expiresAt: Long,
        leaseId: String,
    ): QueueAcquireRequest = QueueAcquireRequest(
        consumerId = QueueConsumerId("consumer-1"),
        leaseId = QueueLeaseId(leaseId),
        acquiredAt = DataLoomInstant(acquiredAt),
        leaseExpiresAt = DataLoomInstant(expiresAt),
        maxEntries = 1,
    )

    private fun ProviderOperationResult<Unit>.assertSuccess() {
        assertIs<ProviderOperationResult.Success<Unit>>(this)
    }

    private fun entry(
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-plan"),
        synchronizationRequest = request(),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        strategyDecision = decision,
        strategyPlan = plan,
    )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-plan"),
        sessionId = SynchronizationSessionId("session-plan"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-plan"),
            correlationId = CorrelationId("correlation-plan"),
        ),
    )

    private fun decision(): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-plan"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(8L),
        disposition = StrategyDisposition.DEFER,
    )

    private fun plan(): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(8L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.RECONCILE,
            ),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
                StrategyProviderCapability.CONFLICT_STATE,
            ),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )

    private data class TestError(
        override val code: ErrorCode = ErrorCode("NETWORK_RETRY"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Retry later.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class OpenedProvider(
        val database: DataLoomRoomDatabase,
        val provider: RoomQueueProvider,
    ) : AutoCloseable {
        override fun close() {
            database.close()
        }
    }

    private companion object {
        const val DATABASE_NAME = "dataloom-strategy-plan-persistence"
        const val MIGRATION_DATABASE_NAME = "dataloom-strategy-plan-migration"
        const val CORRUPTION_DATABASE_NAME = "dataloom-strategy-plan-corruption"
    }
}
