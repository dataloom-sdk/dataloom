from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file_path = Path(path)
    text = file_path.read_text()
    if old not in text:
        raise SystemExit(f"Patch anchor not found in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"Expected one patch anchor in {path}, found {text.count(old)}")
    file_path.write_text(text.replace(old, new, 1))


def replace_exact_count(path: str, old: str, new: str, count: int) -> None:
    file_path = Path(path)
    text = file_path.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"Expected {count} patch anchors in {path}, found {actual}")
    file_path.write_text(text.replace(old, new))


replace_once(
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/queue/QueueEntry.kt",
    "import io.dataloom.api.retry.RetryBudgetState\n",
    "import io.dataloom.api.retry.RetryBudgetState\n"
    "import io.dataloom.api.retry.WorkflowTimeoutState\n",
)
replace_once(
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/queue/QueueEntry.kt",
    "    public val retryBudgetState: RetryBudgetState? = null,\n) {",
    "    public val retryBudgetState: RetryBudgetState? = null,\n\n"
    "    /** Immutable accepted workflow start and absolute deadline evidence. */\n"
    "    public val workflowTimeoutState: WorkflowTimeoutState? = null,\n"
    ") {",
)

replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/submission/QueuedSynchronizationSubmission.kt",
    "import io.dataloom.api.time.DataLoomInstant\n",
    "import io.dataloom.api.retry.WorkflowTimeoutState\n"
    "import io.dataloom.api.time.DataLoomInstant\n",
)
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/submission/QueuedSynchronizationSubmission.kt",
    "    public val availableAt: DataLoomInstant,\n) {",
    "    public val availableAt: DataLoomInstant,\n\n"
    "    /** Immutable workflow timeout evidence to persist with the queue entry. */\n"
    "    public val workflowTimeoutState: WorkflowTimeoutState? = null,\n"
    ") {",
)
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/submission/QueuedSynchronizationSubmission.kt",
    "            availableAt == other.availableAt\n",
    "            availableAt == other.availableAt &&\n"
    "            workflowTimeoutState == other.workflowTimeoutState\n",
)
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/submission/QueuedSynchronizationSubmission.kt",
    "        result = 31 * result + availableAt.hashCode()\n        return result\n",
    "        result = 31 * result + availableAt.hashCode()\n"
    "        result = 31 * result + (workflowTimeoutState?.hashCode() ?: 0)\n"
    "        return result\n",
)
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/submission/QueuedSynchronizationSubmission.kt",
    "            \"availableAt=$availableAt\" +\n            \")\"\n",
    "            \"availableAt=$availableAt, \" +\n"
    "            \"hasWorkflowTimeoutState=${workflowTimeoutState != null}\" +\n"
    "            \")\"\n",
)

replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/submission/QueueSubmissionPreflight.kt",
    "        if (entry.availableAt != submission.availableAt) {\n"
    "            return ContractViolationError(\n"
    "                message = \"Encoded availableAt does not match submission. \" +\n"
    "                    \"Expected ${submission.availableAt.epochMilliseconds} \" +\n"
    "                    \"but encoded entry has ${entry.availableAt.epochMilliseconds}.\",\n"
    "            )\n"
    "        }\n\n"
    "        return null\n",
    "        if (entry.availableAt != submission.availableAt) {\n"
    "            return ContractViolationError(\n"
    "                message = \"Encoded availableAt does not match submission. \" +\n"
    "                    \"Expected ${submission.availableAt.epochMilliseconds} \" +\n"
    "                    \"but encoded entry has ${entry.availableAt.epochMilliseconds}.\",\n"
    "            )\n"
    "        }\n\n"
    "        if (entry.workflowTimeoutState != submission.workflowTimeoutState) {\n"
    "            return ContractViolationError(\n"
    "                message = \"Encoded workflow timeout evidence does not match submission.\",\n"
    "            )\n"
    "        }\n\n"
    "        return null\n",
)

replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/QueueEntryEntity.kt",
    "This entity is part of [DataLoomRoomDatabase] schema version 2.",
    "This entity is part of [DataLoomRoomDatabase] schema version 4.",
)
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/QueueEntryEntity.kt",
    "    @ColumnInfo(name = \"retry_cumulative_delay_ms\")\n"
    "    val retryCumulativeDelayMs: Long?,\n\n"
    "    /** Unique lease identifier; non-null only when state = LEASED. */",
    "    @ColumnInfo(name = \"retry_cumulative_delay_ms\")\n"
    "    val retryCumulativeDelayMs: Long?,\n\n"
    "    /** Accepted workflow timeout start instant; null when no workflow limit was accepted. */\n"
    "    @ColumnInfo(name = \"workflow_started_at_ms\")\n"
    "    val workflowStartedAtMs: Long?,\n\n"
    "    /** Immutable absolute workflow deadline; null when no workflow limit was accepted. */\n"
    "    @ColumnInfo(name = \"workflow_deadline_at_ms\")\n"
    "    val workflowDeadlineAtMs: Long?,\n\n"
    "    /** Unique lease identifier; non-null only when state = LEASED. */",
)

replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt",
    "import io.dataloom.api.retry.RetryBudgetState\n",
    "import io.dataloom.api.retry.RetryBudgetState\n"
    "import io.dataloom.api.retry.WorkflowTimeoutState\n",
)
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt",
    "        retryCumulativeDelayMs = retryBudgetState?.cumulativeDelay?.milliseconds,\n"
    "        leaseId = lease?.id?.value,",
    "        retryCumulativeDelayMs = retryBudgetState?.cumulativeDelay?.milliseconds,\n"
    "        workflowStartedAtMs = workflowTimeoutState?.startedAt?.epochMilliseconds,\n"
    "        workflowDeadlineAtMs = workflowTimeoutState?.deadline?.epochMilliseconds,\n"
    "        leaseId = lease?.id?.value,",
)
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt",
    "    val retryBudgetState = if (budgetColumns.all { it == null }) {\n"
    "        null\n"
    "    } else {\n"
    "        RetryBudgetState(\n"
    "            windowStartedAt = DataLoomInstant(checkNotNull(retryWindowStartedAtMs)),\n"
    "            lastEvaluatedAt = DataLoomInstant(checkNotNull(retryLastEvaluatedAtMs)),\n"
    "            cumulativeDelay = SchedulingDelay(checkNotNull(retryCumulativeDelayMs)),\n"
    "        )\n"
    "    }\n\n"
    "    return QueueEntry(\n",
    "    val retryBudgetState = if (budgetColumns.all { it == null }) {\n"
    "        null\n"
    "    } else {\n"
    "        RetryBudgetState(\n"
    "            windowStartedAt = DataLoomInstant(checkNotNull(retryWindowStartedAtMs)),\n"
    "            lastEvaluatedAt = DataLoomInstant(checkNotNull(retryLastEvaluatedAtMs)),\n"
    "            cumulativeDelay = SchedulingDelay(checkNotNull(retryCumulativeDelayMs)),\n"
    "        )\n"
    "    }\n\n"
    "    val workflowColumns = listOf(workflowStartedAtMs, workflowDeadlineAtMs)\n"
    "    check(workflowColumns.all { it == null } || workflowColumns.all { it != null }) {\n"
    "        \"Persisted workflow-timeout columns must be either all null or all non-null.\"\n"
    "    }\n"
    "    val workflowTimeoutState = if (workflowColumns.all { it == null }) {\n"
    "        null\n"
    "    } else {\n"
    "        WorkflowTimeoutState(\n"
    "            startedAt = DataLoomInstant(checkNotNull(workflowStartedAtMs)),\n"
    "            deadline = DataLoomInstant(checkNotNull(workflowDeadlineAtMs)),\n"
    "        )\n"
    "    }\n\n"
    "    return QueueEntry(\n",
)
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt",
    "        retryBudgetState = retryBudgetState,\n"
    "    )\n",
    "        retryBudgetState = retryBudgetState,\n"
    "        workflowTimeoutState = workflowTimeoutState,\n"
    "    )\n",
)

replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/DataLoomRoomDatabase.kt",
    "    version = 3,",
    "    version = 4,",
)

replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/DataLoomRoomMigrations.kt",
    "    /** Complete ordered migration set used by [DataLoomDatabaseBuilder]. */\n"
    "    public val ALL: Array<Migration>\n"
    "        get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3)\n",
    "    /** Adds nullable immutable workflow timeout evidence to existing queue rows. */\n"
    "    public val MIGRATION_3_4: Migration = object : Migration(3, 4) {\n"
    "        override fun migrate(database: SupportSQLiteDatabase) {\n"
    "            database.execSQL(\n"
    "                \"ALTER TABLE queue_entries ADD COLUMN workflow_started_at_ms INTEGER\",\n"
    "            )\n"
    "            database.execSQL(\n"
    "                \"ALTER TABLE queue_entries ADD COLUMN workflow_deadline_at_ms INTEGER\",\n"
    "            )\n"
    "        }\n"
    "    }\n\n"
    "    /** Complete ordered migration set used by [DataLoomDatabaseBuilder]. */\n"
    "    public val ALL: Array<Migration>\n"
    "        get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)\n",
)

replace_once(
    "dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/DataLoomRoomMigrationTest.kt",
    "    private fun openCurrentDatabase(name: String) {\n",
    "    @Test\n"
    "    fun version3QueueEntryMigratesToVersion4WithoutInventingWorkflowDeadline() {\n"
    "        val version3 = migrationTestHelper.createDatabase(TEST_DATABASE_3_4, 3)\n"
    "        version3.execSQL(\n"
    "            \"\"\"\n"
    "            INSERT INTO queue_entries (\n"
    "                entry_id, workflow_id, session_id, direction, mode, priority,\n"
    "                exec_execution_id, exec_correlation_id, state,\n"
    "                enqueued_at_ms, available_at_ms\n"
    "            ) VALUES (\n"
    "                'entry-003', 'workflow-003', 'session-003', 'PUSH', 'DELTA', 'NORMAL',\n"
    "                'execution-003', 'correlation-003', 'PENDING',\n"
    "                3000, 3000\n"
    "            )\n"
    "            \"\"\".trimIndent(),\n"
    "        )\n"
    "        version3.close()\n\n"
    "        val migrated = migrationTestHelper.runMigrationsAndValidate(\n"
    "            TEST_DATABASE_3_4,\n"
    "            4,\n"
    "            true,\n"
    "            DataLoomRoomMigrations.MIGRATION_3_4,\n"
    "        )\n"
    "        val cursor = migrated.query(\n"
    "            \"\"\"\n"
    "            SELECT workflow_started_at_ms, workflow_deadline_at_ms\n"
    "            FROM queue_entries WHERE entry_id = 'entry-003'\n"
    "            \"\"\".trimIndent(),\n"
    "        )\n"
    "        try {\n"
    "            assertTrue(cursor.moveToFirst())\n"
    "            assertTrue(cursor.isNull(0))\n"
    "            assertTrue(cursor.isNull(1))\n"
    "        } finally {\n"
    "            cursor.close()\n"
    "            migrated.close()\n"
    "        }\n\n"
    "        openCurrentDatabase(TEST_DATABASE_3_4)\n"
    "    }\n\n"
    "    private fun openCurrentDatabase(name: String) {\n",
)
replace_once(
    "dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/DataLoomRoomMigrationTest.kt",
    "        const val TEST_DATABASE_2_3 = \"dataloom-room-migration-2-3-test\"\n",
    "        const val TEST_DATABASE_2_3 = \"dataloom-room-migration-2-3-test\"\n"
    "        const val TEST_DATABASE_3_4 = \"dataloom-room-migration-3-4-test\"\n",
)

replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/QueuedSynchronizationExecutionHandler.kt",
    "import io.dataloom.runtime.retry.SynchronizationRetryEvaluator\n",
    "import io.dataloom.runtime.retry.SynchronizationRetryEvaluator\n"
    "import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor\n",
)
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/QueuedSynchronizationExecutionHandler.kt",
    "    private val clock: DataLoomClock? = null,\n) : QueueEntryExecutionHandler {",
    "    private val clock: DataLoomClock? = null,\n"
    "    private val workflowTimeoutExecutor: WorkflowTimeoutStateExecutor? = null,\n"
    ") : QueueEntryExecutionHandler {",
)
replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/QueuedSynchronizationExecutionHandler.kt",
    "        val executionResult = executionCoordinator.execute(work.request, work.bindings)\n"
    "        if (executionResult is SynchronizationExecutionResult.Rejected) {\n",
    "        val executionResult = when (val timedExecution = executeQueuedWorkflowWithTimeout(\n"
    "            entry = entry,\n"
    "            timeoutExecutor = workflowTimeoutExecutor,\n"
    "        ) {\n"
    "            executionCoordinator.execute(work.request, work.bindings)\n"
    "        }) {\n"
    "            is QueuedWorkflowTimeoutExecution.Completed -> timedExecution.value\n"
    "            is QueuedWorkflowTimeoutExecution.Failed -> {\n"
    "                return QueueEntryExecutionOutcome.Failed(\n"
    "                    error = timedExecution.error,\n"
    "                    disposition = QueueFailureDisposition.FAILED,\n"
    "                )\n"
    "            }\n"
    "        }\n"
    "        if (executionResult is SynchronizationExecutionResult.Rejected) {\n",
)

replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt",
    "import io.dataloom.runtime.retry.SynchronizationRetryEvaluator\n",
    "import io.dataloom.runtime.retry.SynchronizationRetryEvaluator\n"
    "import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor\n",
)
replace_exact_count(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt",
    "            clock = if (connectivityConfiguration != null) deps.clock else null,\n"
    "        )\n",
    "            clock = if (connectivityConfiguration != null) deps.clock else null,\n"
    "            workflowTimeoutExecutor = WorkflowTimeoutStateExecutor(deps.clock),\n"
    "        )\n",
    2,
)
