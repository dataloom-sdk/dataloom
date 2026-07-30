from __future__ import annotations

from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    Path(path).write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one replacement, found {count}")
    write(path, text.replace(old, new, 1))


# Queue runtime propagation.
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/QueuedSynchronizationExecutionHandler.kt",
    "return when (val evaluation = retryEvaluator.evaluate(result, nextAttempt, retryOperation)) {",
    """return when (val evaluation = retryEvaluator.evaluate(
            result = result,
            retryAttempt = nextAttempt,
            retryOperation = retryOperation,
            retryBudgetState = entry.retryBudgetState,
        )) {""",
)
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/QueuedSynchronizationExecutionHandler.kt",
    """                    error = evaluation.error,
                )""",
    """                    error = evaluation.error,
                    retryBudgetState = evaluation.retryBudgetState,
                )""",
)
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/DurableQueueExecutionProcessor.kt",
    """                        availableAt = outcome.availableAt,
                        error = outcome.error,
                    ),""",
    """                        availableAt = outcome.availableAt,
                        error = outcome.error,
                        retryBudgetState = outcome.retryBudgetState,
                    ),""",
)

# In-memory provider persistence and restart semantics.
replace_once(
    "dataloom-testing/src/commonMain/kotlin/io/dataloom/testing/queue/InMemoryQueueProvider.kt",
    """            retryAttempt = null,
            lastError = null,""",
    """            retryAttempt = null,
            retryBudgetState = null,
            lastError = null,""",
)
replace_once(
    "dataloom-testing/src/commonMain/kotlin/io/dataloom/testing/queue/InMemoryQueueProvider.kt",
    """                retryAttempt = request.retryAttempt,
                lease = null,""",
    """                retryAttempt = request.retryAttempt,
                retryBudgetState = request.retryBudgetState,
                lease = null,""",
)

# Room entity and mappers.
entity_path = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/QueueEntryEntity.kt"
replace_once(entity_path, "This entity is part of [DataLoomRoomDatabase] schema version 1.",
             "This entity is part of [DataLoomRoomDatabase] schema version 2.")
replace_once(
    entity_path,
    """    @ColumnInfo(name = "retry_attempt_number")
    val retryAttemptNumber: Int?,

    /** Unique lease identifier; non-null only when state = LEASED. */""",
    """    @ColumnInfo(name = "retry_attempt_number")
    val retryAttemptNumber: Int?,

    /** First genuine retry-budget evaluation instant; null when budgets are disabled. */
    @ColumnInfo(name = "retry_window_started_at_ms")
    val retryWindowStartedAtMs: Long?,

    /** Most recent accepted retry-budget evaluation instant. */
    @ColumnInfo(name = "retry_last_evaluated_at_ms")
    val retryLastEvaluatedAtMs: Long?,

    /** Sum of delays accepted for durable retry transitions. */
    @ColumnInfo(name = "retry_cumulative_delay_ms")
    val retryCumulativeDelayMs: Long?,

    /** Unique lease identifier; non-null only when state = LEASED. */""",
)

mappers_path = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt"
replace_once(
    mappers_path,
    "import io.dataloom.api.retry.RetryAttempt\n",
    """import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.scheduling.SchedulingDelay
""",
)
replace_once(
    mappers_path,
    """        retryAttemptNumber = retryAttempt?.number,
        leaseId = lease?.id?.value,""",
    """        retryAttemptNumber = retryAttempt?.number,
        retryWindowStartedAtMs = retryBudgetState?.windowStartedAt?.epochMilliseconds,
        retryLastEvaluatedAtMs = retryBudgetState?.lastEvaluatedAt?.epochMilliseconds,
        retryCumulativeDelayMs = retryBudgetState?.cumulativeDelay?.milliseconds,
        leaseId = lease?.id?.value,""",
)
replace_once(
    mappers_path,
    """    val retryAttempt: RetryAttempt? = retryAttemptNumber?.let { RetryAttempt(it) }

    return QueueEntry(""",
    """    val retryAttempt: RetryAttempt? = retryAttemptNumber?.let { RetryAttempt(it) }
    val budgetColumns = listOf(
        retryWindowStartedAtMs,
        retryLastEvaluatedAtMs,
        retryCumulativeDelayMs,
    )
    check(budgetColumns.all { it == null } || budgetColumns.all { it != null }) {
        "Persisted retry-budget columns must be either all null or all non-null."
    }
    val retryBudgetState = if (budgetColumns.all { it == null }) {
        null
    } else {
        RetryBudgetState(
            windowStartedAt = DataLoomInstant(checkNotNull(retryWindowStartedAtMs)),
            lastEvaluatedAt = DataLoomInstant(checkNotNull(retryLastEvaluatedAtMs)),
            cumulativeDelay = SchedulingDelay(checkNotNull(retryCumulativeDelayMs)),
        )
    }

    return QueueEntry(""",
)
replace_once(
    mappers_path,
    """        lastError = lastError,
        metadata = entryMeta,
    )""",
    """        lastError = lastError,
        metadata = entryMeta,
        retryBudgetState = retryBudgetState,
    )""",
)

# Room DAO atomic reschedule state.
dao_path = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/QueueEntryDao.kt"
replace_once(
    dao_path,
    """            retry_attempt_number = :retryAttemptNumber,
            last_error_code = :errorCode,""",
    """            retry_attempt_number = :retryAttemptNumber,
            retry_window_started_at_ms = :retryWindowStartedAtMs,
            retry_last_evaluated_at_ms = :retryLastEvaluatedAtMs,
            retry_cumulative_delay_ms = :retryCumulativeDelayMs,
            last_error_code = :errorCode,""",
)
replace_once(
    dao_path,
    """        retryAttemptNumber: Int,
        errorCode: String,""",
    """        retryAttemptNumber: Int,
        retryWindowStartedAtMs: Long?,
        retryLastEvaluatedAtMs: Long?,
        retryCumulativeDelayMs: Long?,
        errorCode: String,""",
)

provider_path = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/RoomQueueProvider.kt"
replace_once(
    provider_path,
    """                retryAttemptNumber = request.retryAttempt.number,
                errorCode = request.error.code.value,""",
    """                retryAttemptNumber = request.retryAttempt.number,
                retryWindowStartedAtMs = request.retryBudgetState?.windowStartedAt?.epochMilliseconds,
                retryLastEvaluatedAtMs = request.retryBudgetState?.lastEvaluatedAt?.epochMilliseconds,
                retryCumulativeDelayMs = request.retryBudgetState?.cumulativeDelay?.milliseconds,
                errorCode = request.error.code.value,""",
)

# Database version and production migration installation.
db_path = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/DataLoomRoomDatabase.kt"
replace_once(db_path, "version = 1,", "version = 2,")
builder_path = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/DataLoomDatabaseBuilder.kt"
replace_once(
    builder_path,
    """            DataLoomRoomDatabase::class.java,
            name,
        ).build()""",
    """            DataLoomRoomDatabase::class.java,
            name,
        ).addMigrations(*DataLoomRoomMigrations.ALL)
            .build()""",
)

# Test fixtures retain existing source call order and support optional budgets.
fixtures_path = "dataloom-testing/src/commonTest/kotlin/io/dataloom/testing/TestFixtures.kt"
replace_once(
    fixtures_path,
    "import io.dataloom.api.retry.RetryAttempt\n",
    """import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
""",
)
replace_once(
    fixtures_path,
    """    retryAttempt: RetryAttempt? = null,
    lease: QueueLease? = null,""",
    """    retryAttempt: RetryAttempt? = null,
    retryBudgetState: RetryBudgetState? = null,
    lease: QueueLease? = null,""",
)
replace_once(
    fixtures_path,
    """    lastError = lastError,
)""",
    """    lastError = lastError,
    retryBudgetState = retryBudgetState,
)""",
)

# External-consumer compilation probes for all new public contracts.
consumer_path = "runtime-external-consumer/src/commonMain/kotlin/io/dataloom/consumer/RuntimeExternalConsumerProbe.kt"
replace_once(
    consumer_path,
    "import io.dataloom.api.retry.RetryDecision\n",
    """import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryDecision
""",
)
replace_once(
    consumer_path,
    "import io.dataloom.runtime.retry.RetryBackoffStrategy\n",
    """import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.RetryBudgetConfiguration
""",
)
replace_once(
    consumer_path,
    """    randomSource.sample(randomRequest)
    return jitteredPolicy.evaluate(request)
}""",
    """    randomSource.sample(randomRequest)
    return jitteredPolicy.evaluate(request)
}

/** Compile-only use of durable retry budget state and configuration. */
internal fun compileRetryBudgetConsumer(
    request: RetryEvaluationRequest,
    state: RetryBudgetState,
): RetryDecision {
    val configuration = RetryBudgetConfiguration(
        maximumElapsedTime = SchedulingDelay(120_000L),
        maximumCumulativeDelay = SchedulingDelay(90_000L),
    )
    state.windowStartedAt
    state.lastEvaluatedAt
    state.cumulativeDelay
    configuration.maximumElapsedTime
    configuration.maximumCumulativeDelay
    return StandardRetryPolicy(
        id = RetryPolicyId("external-budget-policy"),
        strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(1_000L)),
        maximumAttempts = 3,
    ).evaluate(request)
}""",
)

# Android schema validation follows the new current schema.
workflow_path = ".github/workflows/android-validation.yml"
replace_once(
    workflow_path,
    "dataloom-queue-room/schemas/io.dataloom.queue.room.internal.DataLoomRoomDatabase/1.json",
    "dataloom-queue-room/schemas/io.dataloom.queue.room.internal.DataLoomRoomDatabase/2.json",
)
replace_once(workflow_path, "Missing committed Room schema version 1.",
             "Missing committed Room schema version 2.")

# Production migration test with preservation evidence.
write(
    "dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/DataLoomRoomMigrationTest.kt",
    '''package io.dataloom.queue.room

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies non-destructive queue schema migration and current database opening. */
@RunWith(AndroidJUnit4::class)
class DataLoomRoomMigrationTest {

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DataLoomRoomDatabase::class.java,
    )

    @Test
    fun version1RetryEntryMigratesToVersion2WithoutLosingHistory() {
        val version1 = migrationTestHelper.createDatabase(TEST_DATABASE, 1)
        version1.execSQL(
            """
            INSERT INTO queue_entries (
                entry_id, workflow_id, session_id, direction, mode, priority,
                exec_execution_id, exec_correlation_id, state,
                enqueued_at_ms, available_at_ms, retry_attempt_number
            ) VALUES (
                'entry-001', 'workflow-001', 'session-001', 'PUSH', 'DELTA', 'NORMAL',
                'execution-001', 'correlation-001', 'RETRY_WAITING',
                1000, 5000, 2
            )
            """.trimIndent(),
        )
        version1.close()

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            DataLoomRoomMigrations.MIGRATION_1_2,
        )
        val cursor = migrated.query(
            """
            SELECT retry_attempt_number, available_at_ms,
                   retry_window_started_at_ms, retry_last_evaluated_at_ms,
                   retry_cumulative_delay_ms
            FROM queue_entries WHERE entry_id = 'entry-001'
            """.trimIndent(),
        )
        try {
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
            assertEquals(5_000L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        } finally {
            cursor.close()
            migrated.close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(
            context,
            DataLoomRoomDatabase::class.java,
            TEST_DATABASE,
        ).addMigrations(DataLoomRoomMigrations.MIGRATION_1_2)
            .build()
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "dataloom-room-migration-test"
    }
}
''',
)

# Current-state documentation.
readme_path = "README.md"
replace_once(
    readme_path,
    "Fail-closed classification, deterministic standard backoff and jitter, attempt budget, queue/scheduler orchestration, and restart-safe attempt history implemented; broader engine partial",
    "Fail-closed classification, deterministic backoff/jitter, attempt plus durable elapsed/cumulative budgets, queue/scheduler orchestration, and restart-safe history implemented; broader engine partial",
)
replace_once(
    readme_path,
    "Elapsed/delay budgets, hints, timeout separation, durable circuit state, operations, and full qualification",
    "Hints, timeout separation, durable circuit state, operations, and full qualification",
)

api_index = "docs/api/README.md"
replace_once(
    api_index,
    "V1 requires built-in retry strategies, jitter, limits, server hints, durable",
    "V1 has built-in retry strategies, jitter, attempt and durable time/delay budgets. Server hints and durable",
)
replace_once(
    api_index,
    "circuit-breaker and half-open recovery, restart-safe policy state, manual",
    "circuit-breaker and half-open recovery, manual",
)

retry_doc = "docs/api/retry-policy.md"
replace_once(
    retry_doc,
    "and an attempt budget are implemented. Elapsed-time and aggregate-delay",
    "attempt budget, and durable elapsed-time/cumulative-delay budgets are implemented. Provider",
)
replace_once(
    retry_doc,
    "budgets, server hints, timeout separation, durable circuit breaking,",
    "hints, timeout separation, durable circuit breaking,",
)
replace_once(
    retry_doc,
    "- maximum elapsed-time and aggregate-delay budgets;",
    "- bounded provider/server retry hints;",
)
replace_once(
    retry_doc,
    "- bounded provider/server retry hints;\n- separated timeout semantics;",
    "- separated timeout semantics;",
)
write(
    retry_doc,
    read(retry_doc).replace(
        "## Runtime integration\n",
        """## Durable retry budgets

`RetryBudgetConfiguration` independently limits elapsed wall-clock time and the
sum of accepted retry delays. Exact boundaries are accepted; a proposed retry
that would exceed a limit stops with a stable `RetryStopReason`. Clock regression
against persisted evidence stops fail-closed.

`RetryBudgetState` records the first genuine failure instant, the latest accepted
evaluation instant, and cumulative accepted delay. Queue providers persist this
state atomically with retry rescheduling, preserve it through connectivity
deferral and expired-lease recovery, and migrate version-1 entries without
fabricating historical budget values.

Direct scheduler orchestration returns the next budget state only after the
scheduler accepts work. Missing or failed scheduling never consumes budget.

## Runtime integration
""",
        1,
    ),
)

boundaries_doc = "docs/architecture/retry-boundaries.md"
replace_once(
    boundaries_doc,
    "backoff with an attempt budget. Durable circuit breaking and the remaining\n> time, jitter, hint, observability, and administration gates are incomplete",
    "backoff with attempt, elapsed-time, and cumulative-delay budgets. Durable\n> circuit breaking and the remaining hint, timeout, observability, and administration gates are incomplete",
)
replace_once(
    boundaries_doc,
    "- maximum retry attempts.",
    """- maximum retry attempts;
- maximum elapsed retry time; and
- maximum cumulative accepted delay.""",
)
replace_once(
    boundaries_doc,
    "The standard policy does not own queue transitions, clocks, elapsed windows,\nscheduler invocation, circuit persistence, provider retry hints, or manual",
    "The standard policy does not own queue transitions, clocks, scheduler invocation,\ncircuit persistence, provider retry hints, or manual",
)
replace_once(
    boundaries_doc,
    "- future elapsed and aggregate budgets, jitter/random boundaries, circuit state,\n  hints, manual operations, and observability.",
    "- durable elapsed/cumulative budget evaluation and state propagation; and\n- future circuit state, hints, manual operations, and observability.",
)

room_doc = "docs/android/room-queue-provider.md"
text = read(room_doc)
if "retry budget" not in text.lower():
    text += """

## Retry budget persistence and migration

Schema version 2 adds nullable first-evaluation, last-evaluation, and cumulative-
delay columns. `MIGRATION_1_2` is non-destructive: existing retry attempt and
availability values are preserved, while historical budget fields remain null.
A successful retry reschedule writes attempt, availability, error, and budget
state in one lease-guarded update. Connectivity deferral and expired-lease
recovery do not modify budget state.
"""
    write(room_doc, text)
