from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.rstrip() + "\n")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}: {old[:80]!r}")
    write(path, content.replace(old, new, 1))


def regex_replace_once(path: str, pattern: str, replacement: str) -> None:
    content = read(path)
    updated, count = re.subn(pattern, replacement, content, count=1, flags=re.DOTALL)
    if count != 1:
        raise SystemExit(f"Expected one regex match in {path}, found {count}: {pattern[:80]!r}")
    write(path, updated)


def append_section(path: str, marker: str, section: str) -> None:
    content = read(path)
    if marker in content:
        raise SystemExit(f"Marker already present in {path}: {marker}")
    write(path, content.rstrip() + "\n\n" + section.strip() + "\n")


# ---------------------------------------------------------------------------
# Public strategy and queue contracts
# ---------------------------------------------------------------------------
replace_once(
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/StrategyEvaluation.kt",
    """public data class PersistedStrategyDecision(
    public val decisionId: StrategyDecisionId,
    public val planId: StrategyPlanId,
    public val requestedStrategy: BuiltInSynchronizationStrategy,
    public val effectiveProfileId: StrategyProfileId,
    public val effectiveStrategy: BuiltInSynchronizationStrategy,
    public val configurationVersion: StrategyConfigurationVersion,
    public val disposition: StrategyDisposition,
)
""",
    """public data class PersistedStrategyDecision(
    public val decisionId: StrategyDecisionId,
    public val planId: StrategyPlanId,
    public val requestedStrategy: BuiltInSynchronizationStrategy,
    public val effectiveProfileId: StrategyProfileId,
    public val effectiveStrategy: BuiltInSynchronizationStrategy,
    public val configurationVersion: StrategyConfigurationVersion,
    public val disposition: StrategyDisposition,
) {
    init {
        require(effectiveStrategy != BuiltInSynchronizationStrategy.ADAPTIVE) {
            "PersistedStrategyDecision effectiveStrategy must be concrete."
        }
        require(disposition != StrategyDisposition.REJECT) {
            "Rejected strategy decisions must not be persisted as durable work."
        }
    }
}
""",
)

replace_once(
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/queue/QueueEntry.kt",
    "import io.dataloom.api.retry.WorkflowTimeoutState\n",
    "import io.dataloom.api.retry.WorkflowTimeoutState\nimport io.dataloom.api.strategy.PersistedStrategyDecision\n",
)
replace_once(
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/queue/QueueEntry.kt",
    """    /** Immutable accepted workflow start and absolute deadline evidence. */
    public val workflowTimeoutState: WorkflowTimeoutState? = null,
) {
""",
    """    /** Immutable accepted workflow start and absolute deadline evidence. */
    public val workflowTimeoutState: WorkflowTimeoutState? = null,

    /** Immutable strategy identity accepted before durable queue admission. */
    public val strategyDecision: PersistedStrategyDecision? = null,
) {
""",
)

write(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/QueuedSynchronizationWork.kt",
    r'''package io.dataloom.runtime.queue

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision

/**
 * Immutable work resolved from, or prepared for, one durable queue entry.
 *
 * [request], [bindings], and optional [strategyDecision] are preserved exactly.
 * A non-null strategy decision is the immutable identity selected before queue
 * admission. Retry, deferral, lease recovery, process restart, and later
 * configuration changes must not replace it.
 *
 * Construction performs no provider access, strategy evaluation, clock read,
 * identifier generation, queue transition, scheduling, or I/O.
 */
public class QueuedSynchronizationWork(
    /** Exact synchronization request carried by the queue entry. */
    public val request: SynchronizationRequest,

    /** Exact provider bindings required to execute the request. */
    public val bindings: SynchronizationProviderBindings,

    /** Optional immutable strategy decision associated with durable work. */
    public val strategyDecision: PersistedStrategyDecision? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueuedSynchronizationWork) return false
        return request == other.request &&
            bindings == other.bindings &&
            strategyDecision == other.strategyDecision
    }

    override fun hashCode(): Int {
        var result = request.hashCode()
        result = 31 * result + bindings.hashCode()
        result = 31 * result + (strategyDecision?.hashCode() ?: 0)
        return result
    }

    /** Safe diagnostic representation that excludes payloads and strategy IDs. */
    override fun toString(): String =
        "QueuedSynchronizationWork(" +
            "workflowId=${request.workflowId.value}, " +
            "sessionId=${request.sessionId.value}, " +
            "direction=${request.direction}, " +
            "storageProviderId=${bindings.storageProviderId.value}, " +
            "transportProviderId=${bindings.transportProviderId.value}, " +
            "hasStrategyDecision=${strategyDecision != null}" +
            ")"
}
''',
)

replace_once(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/submission/QueueSubmissionPreflight.kt",
    """        if (entry.workflowTimeoutState != submission.workflowTimeoutState) {
            return ContractViolationError(
                message = "Encoded workflow timeout evidence does not match submission.",
            )
        }

        return null
""",
    """        if (entry.workflowTimeoutState != submission.workflowTimeoutState) {
            return ContractViolationError(
                message = "Encoded workflow timeout evidence does not match submission.",
            )
        }

        if (entry.strategyDecision != submission.work.strategyDecision) {
            return ContractViolationError(
                message = "Encoded strategy decision does not match submitted work.",
            )
        }

        return null
""",
)

# ---------------------------------------------------------------------------
# Android Room persistence and migration
# ---------------------------------------------------------------------------
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/QueueEntryEntity.kt",
    " * This entity is part of [DataLoomRoomDatabase] schema version 4.\n",
    " * This entity is part of [DataLoomRoomDatabase] schema version 7.\n",
)
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/QueueEntryEntity.kt",
    """    @ColumnInfo(name = "entry_metadata_json")
    val entryMetadataJson: String?,
)
""",
    """    @ColumnInfo(name = "entry_metadata_json")
    val entryMetadataJson: String?,

    /** Strategy decision identifier; null for legacy or non-strategy work. */
    @ColumnInfo(name = "strategy_decision_id")
    val strategyDecisionId: String?,

    /** Immutable strategy plan identifier. */
    @ColumnInfo(name = "strategy_plan_id")
    val strategyPlanId: String?,

    /** Requested built-in strategy enum name. */
    @ColumnInfo(name = "strategy_requested_strategy")
    val strategyRequestedStrategy: String?,

    /** Effective profile identifier selected before admission. */
    @ColumnInfo(name = "strategy_effective_profile_id")
    val strategyEffectiveProfileId: String?,

    /** Concrete effective built-in strategy enum name. */
    @ColumnInfo(name = "strategy_effective_strategy")
    val strategyEffectiveStrategy: String?,

    /** Immutable strategy configuration version. */
    @ColumnInfo(name = "strategy_configuration_version")
    val strategyConfigurationVersion: Long?,

    /** Accepted strategy disposition enum name. */
    @ColumnInfo(name = "strategy_disposition")
    val strategyDisposition: String?,
)
""",
)

replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt",
    "import io.dataloom.api.retry.WorkflowTimeoutState\n",
    """import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
""",
)
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt",
    """        lastErrorMessage = lastError?.message,
        entryMetadataJson = metadata.toJsonOrNull(),
    )
""",
    """        lastErrorMessage = lastError?.message,
        entryMetadataJson = metadata.toJsonOrNull(),
        strategyDecisionId = strategyDecision?.decisionId?.value,
        strategyPlanId = strategyDecision?.planId?.value,
        strategyRequestedStrategy = strategyDecision?.requestedStrategy?.name,
        strategyEffectiveProfileId = strategyDecision?.effectiveProfileId?.value,
        strategyEffectiveStrategy = strategyDecision?.effectiveStrategy?.name,
        strategyConfigurationVersion = strategyDecision?.configurationVersion?.value,
        strategyDisposition = strategyDecision?.disposition?.name,
    )
""",
)
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt",
    """    val workflowTimeoutState = if (workflowColumns.all { it == null }) {
        null
    } else {
        WorkflowTimeoutState(
            startedAt = DataLoomInstant(checkNotNull(workflowStartedAtMs)),
            deadline = DataLoomInstant(checkNotNull(workflowDeadlineAtMs)),
        )
    }

    return QueueEntry(
""",
    """    val workflowTimeoutState = if (workflowColumns.all { it == null }) {
        null
    } else {
        WorkflowTimeoutState(
            startedAt = DataLoomInstant(checkNotNull(workflowStartedAtMs)),
            deadline = DataLoomInstant(checkNotNull(workflowDeadlineAtMs)),
        )
    }

    val strategyColumns: List<Any?> = listOf(
        strategyDecisionId,
        strategyPlanId,
        strategyRequestedStrategy,
        strategyEffectiveProfileId,
        strategyEffectiveStrategy,
        strategyConfigurationVersion,
        strategyDisposition,
    )
    check(strategyColumns.all { it == null } || strategyColumns.all { it != null }) {
        "Persisted strategy-decision columns must be either all null or all non-null."
    }
    val strategyDecision = if (strategyColumns.all { it == null }) {
        null
    } else {
        PersistedStrategyDecision(
            decisionId = StrategyDecisionId(checkNotNull(strategyDecisionId)),
            planId = StrategyPlanId(checkNotNull(strategyPlanId)),
            requestedStrategy = BuiltInSynchronizationStrategy.valueOf(
                checkNotNull(strategyRequestedStrategy),
            ),
            effectiveProfileId = StrategyProfileId(
                checkNotNull(strategyEffectiveProfileId),
            ),
            effectiveStrategy = BuiltInSynchronizationStrategy.valueOf(
                checkNotNull(strategyEffectiveStrategy),
            ),
            configurationVersion = StrategyConfigurationVersion(
                checkNotNull(strategyConfigurationVersion),
            ),
            disposition = StrategyDisposition.valueOf(checkNotNull(strategyDisposition)),
        )
    }

    return QueueEntry(
""",
)
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt",
    """        retryBudgetState = retryBudgetState,
        workflowTimeoutState = workflowTimeoutState,
    )
""",
    """        retryBudgetState = retryBudgetState,
        workflowTimeoutState = workflowTimeoutState,
        strategyDecision = strategyDecision,
    )
""",
)

replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/DataLoomRoomDatabase.kt",
    "    version = 6,\n",
    "    version = 7,\n",
)

replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/DataLoomRoomMigrations.kt",
    """    /** Complete ordered migration set used by [DataLoomDatabaseBuilder]. */
    public val ALL: Array<Migration>
""",
    """    /** Adds nullable immutable strategy-decision identity to durable queue rows. */
    public val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN strategy_decision_id TEXT")
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN strategy_plan_id TEXT")
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN strategy_requested_strategy TEXT")
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN strategy_effective_profile_id TEXT")
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN strategy_effective_strategy TEXT")
            db.execSQL(
                "ALTER TABLE queue_entries ADD COLUMN strategy_configuration_version INTEGER",
            )
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN strategy_disposition TEXT")
        }
    }

    /** Complete ordered migration set used by [DataLoomDatabaseBuilder]. */
    public val ALL: Array<Migration>
""",
)
replace_once(
    "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/DataLoomRoomMigrations.kt",
    """            MIGRATION_4_5,
            MIGRATION_5_6,
        )
""",
    """            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
        )
""",
)

replace_once(
    "dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/DataLoomRoomMigrationTest.kt",
    "/** Verifies non-destructive queue, circuit, and retry-administration schema migrations. */\n",
    "/** Verifies every supported non-destructive DataLoom Room schema migration. */\n",
)
replace_once(
    "dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/DataLoomRoomMigrationTest.kt",
    """    private fun openCurrentDatabase(name: String) {
""",
    r'''    @Test
    fun version6MigratesToVersion7WithoutInventingStrategyDecision() {
        val version6 = migrationTestHelper.createDatabase(TEST_DATABASE_6_7, 6)
        version6.execSQL(
            """
            INSERT INTO queue_entries (
                entry_id, workflow_id, session_id, direction, mode, priority,
                exec_execution_id, exec_correlation_id, state,
                enqueued_at_ms, available_at_ms
            ) VALUES (
                'entry-006', 'workflow-006', 'session-006', 'PUSH', 'DELTA', 'NORMAL',
                'execution-006', 'correlation-006', 'PENDING',
                6000, 6000
            )
            """.trimIndent(),
        )
        version6.close()

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            TEST_DATABASE_6_7,
            7,
            true,
            DataLoomRoomMigrations.MIGRATION_6_7,
        )
        val cursor = migrated.query(
            """
            SELECT strategy_decision_id, strategy_plan_id,
                   strategy_requested_strategy, strategy_effective_profile_id,
                   strategy_effective_strategy, strategy_configuration_version,
                   strategy_disposition
            FROM queue_entries WHERE entry_id = 'entry-006'
            """.trimIndent(),
        )
        try {
            assertTrue(cursor.moveToFirst())
            for (index in 0 until cursor.columnCount) {
                assertTrue(cursor.isNull(index))
            }
        } finally {
            cursor.close()
            migrated.close()
        }

        openCurrentDatabase(TEST_DATABASE_6_7)
    }

    private fun openCurrentDatabase(name: String) {
''',
)
replace_once(
    "dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/DataLoomRoomMigrationTest.kt",
    """        const val TEST_DATABASE_4_5 = "dataloom-room-migration-4-5-test"
        const val TEST_DATABASE_5_6 = "dataloom-room-migration-5-6-test"
""",
    """        const val TEST_DATABASE_4_5 = "dataloom-room-migration-4-5-test"
        const val TEST_DATABASE_5_6 = "dataloom-room-migration-5-6-test"
        const val TEST_DATABASE_6_7 = "dataloom-room-migration-6-7-test"
""",
)

# ---------------------------------------------------------------------------
# Apple snapshot version 3 with backward reads for versions 1 and 2
# ---------------------------------------------------------------------------
replace_once(
    "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleQueueStateFileCodec.kt",
    "import io.dataloom.api.scheduling.SchedulingDelay\n",
    """import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
""",
)
replace_once(
    "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleQueueStateFileCodec.kt",
    """    private const val LEGACY_HEADER: String = "DATALOOM_QUEUE_STATE\\t1"
    private const val CURRENT_HEADER: String = "DATALOOM_QUEUE_STATE\\t2"
    private const val ENTRY_PREFIX: String = "E"
    private const val RECEIPT_PREFIX: String = "R"
    private const val ENTRY_FIELD_COUNT: Int = 35
    private const val RECEIPT_FIELD_COUNT: Int = 13
""",
    """    private const val LEGACY_HEADER: String = "DATALOOM_QUEUE_STATE\\t1"
    private const val VERSION_TWO_HEADER: String = "DATALOOM_QUEUE_STATE\\t2"
    private const val CURRENT_HEADER: String = "DATALOOM_QUEUE_STATE\\t3"
    private const val ENTRY_PREFIX: String = "E"
    private const val RECEIPT_PREFIX: String = "R"
    private const val LEGACY_ENTRY_FIELD_COUNT: Int = 35
    private const val CURRENT_ENTRY_FIELD_COUNT: Int = 42
    private const val RECEIPT_FIELD_COUNT: Int = 13
""",
)
replace_once(
    "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleQueueStateFileCodec.kt",
    "/** Decodes either supported format and returns only queue entries. */\n",
    "/** Decodes any supported historical or current format and returns queue entries. */\n",
)
replace_once(
    "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleQueueStateFileCodec.kt",
    "/** Encodes the current version-2 entry-plus-receipt snapshot. */\n",
    "/** Encodes the current version-3 entry-plus-receipt snapshot. */\n",
)
replace_once(
    "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleQueueStateFileCodec.kt",
    "/** Decodes version 1 or version 2 and reconstructs the complete snapshot. */\n",
    "/** Decodes versions 1, 2, or 3 and reconstructs the complete snapshot. */\n",
)
replace_once(
    "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleQueueStateFileCodec.kt",
    """            when (lines.first()) {
                LEGACY_HEADER -> decodeLegacyLines(lines)
                CURRENT_HEADER -> decodeCurrentLines(lines)
                else -> error("Unsupported Apple queue snapshot version.")
            }
""",
    """            when (lines.first()) {
                LEGACY_HEADER -> decodeLegacyLines(lines)
                VERSION_TWO_HEADER -> decodeVersionTwoLines(lines)
                CURRENT_HEADER -> decodeCurrentLines(lines)
                else -> error("Unsupported Apple queue snapshot version.")
            }
""",
)

codec_path = "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleQueueStateFileCodec.kt"
codec = read(codec_path)
first = codec.find("                append(encodeEntry(entry))")
if first < 0:
    raise SystemExit("Missing current Apple encodeEntry call")
codec = codec[:first] + codec[first:].replace(
    "                append(encodeEntry(entry))",
    "                append(encodeEntry(entry, includeStrategyDecision = true))",
    1,
)
second = codec.find("                append(encodeEntry(entry))")
if second < 0:
    raise SystemExit("Missing legacy Apple encodeEntry call")
codec = codec[:second] + codec[second:].replace(
    "                append(encodeEntry(entry))",
    "                append(encodeEntry(entry, includeStrategyDecision = false))",
    1,
)
write(codec_path, codec)

regex_replace_once(
    codec_path,
    r"    private fun encodeLegacy\(entries: Map<String, QueueEntry>\): String \{.*?(?=    private fun decodeLegacyLines)",
    r'''    private fun encodeLegacy(entries: Map<String, QueueEntry>): String {
        if (entries.size > APPLE_QUEUE_MAX_ENTRY_COUNT) {
            throw AppleQueueEntryLimitException()
        }
        val content = buildString {
            append(LEGACY_HEADER)
            append('\n')
            entries.entries.sortedBy { it.key }.forEach { mapEntry ->
                val id = mapEntry.key
                val entry = mapEntry.value
                check(id == entry.id.value) {
                    "Queue snapshot map key does not match the entry identifier."
                }
                check(entry.strategyDecision == null) {
                    "Version-1 queue snapshots cannot encode a strategy decision."
                }
                append(encodeEntry(entry, includeStrategyDecision = false))
                append('\n')
            }
        }
        ensureFileBound(content)
        return content
    }

''',
)

regex_replace_once(
    codec_path,
    r"    private fun decodeLegacyLines\(lines: List<String>\): AppleQueueSnapshot \{.*?(?=    private inline fun forEachDataLine)",
    r'''    private fun decodeLegacyLines(lines: List<String>): AppleQueueSnapshot {
        val entries = linkedMapOf<String, QueueEntry>()
        forEachDataLine(lines) { line ->
            if (entries.size >= APPLE_QUEUE_MAX_ENTRY_COUNT) {
                throw AppleQueueEntryLimitException()
            }
            val entry = decodeEntry(line, includeStrategyDecision = false)
            require(entries.put(entry.id.value, entry) == null)
        }
        return AppleQueueSnapshot(entries = entries)
    }

    private fun decodeVersionTwoLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(lines, includeStrategyDecision = false)

    private fun decodeCurrentLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(lines, includeStrategyDecision = true)

    private fun decodePrefixedLines(
        lines: List<String>,
        includeStrategyDecision: Boolean,
    ): AppleQueueSnapshot {
        val snapshot = AppleQueueSnapshot()
        forEachDataLine(lines) { line ->
            val separator = line.indexOf('\t')
            require(separator == 1)
            val prefix = line.substring(0, separator)
            val payload = line.substring(separator + 1)
            require(payload.isNotEmpty())
            when (prefix) {
                ENTRY_PREFIX -> {
                    if (snapshot.entries.size >= APPLE_QUEUE_MAX_ENTRY_COUNT) {
                        throw AppleQueueEntryLimitException()
                    }
                    val entry = decodeEntry(payload, includeStrategyDecision)
                    require(snapshot.entries.put(entry.id.value, entry) == null)
                }
                RECEIPT_PREFIX -> {
                    if (snapshot.retryAdministrationReceipts.size >=
                        APPLE_QUEUE_MAX_RETRY_ADMINISTRATION_RECEIPT_COUNT
                    ) {
                        throw AppleQueueReceiptLimitException()
                    }
                    val receipt = decodeReceipt(payload)
                    val commandId = receipt.command.request.commandId.value
                    require(
                        snapshot.retryAdministrationReceipts.put(commandId, receipt) == null,
                    )
                }
                else -> error("Unknown Apple queue snapshot record prefix.")
            }
        }
        return snapshot
    }

''',
)

regex_replace_once(
    codec_path,
    r"    private fun encodeEntry\(entry: QueueEntry\): String \{.*\n\}\s*$",
    r'''    private fun encodeEntry(
        entry: QueueEntry,
        includeStrategyDecision: Boolean,
    ): String {
        val request = entry.synchronizationRequest
        val context = request.context
        val lease = entry.lease
        val error = entry.lastError
        val fields = mutableListOf(
            appleQueueHexEncode(entry.id.value),
            appleQueueHexEncode(request.workflowId.value),
            appleQueueHexEncode(request.sessionId.value),
            request.direction.name,
            request.mode.name,
            request.priority.name,
            appleQueueHexEncode(context.executionId.value),
            appleQueueHexEncode(context.correlationId.value),
            appleQueueEncodeNullableString(context.traceId?.value),
            appleQueueEncodeNullableString(context.requestId?.value),
            appleQueueEncodeNullableString(context.tenantId?.value),
            appleQueueEncodeNullableString(context.userId?.value),
            appleQueueEncodeNullableString(context.localeTag?.value),
            appleQueueEncodeNullableString(context.runtimeVersion?.value),
            appleQueueEncodeNullableString(context.configurationVersion?.value),
            appleQueueEncodeMetadata(context.metadata),
            entry.state.name,
            entry.enqueuedAt.epochMilliseconds.toString(),
            entry.availableAt.epochMilliseconds.toString(),
            appleQueueEncodeNullableInt(entry.retryAttempt?.number),
            appleQueueEncodeNullableLong(entry.retryBudgetState?.windowStartedAt?.epochMilliseconds),
            appleQueueEncodeNullableLong(entry.retryBudgetState?.lastEvaluatedAt?.epochMilliseconds),
            appleQueueEncodeNullableLong(entry.retryBudgetState?.cumulativeDelay?.milliseconds),
            appleQueueEncodeNullableLong(entry.workflowTimeoutState?.startedAt?.epochMilliseconds),
            appleQueueEncodeNullableLong(entry.workflowTimeoutState?.deadline?.epochMilliseconds),
            appleQueueEncodeNullableString(lease?.id?.value),
            appleQueueEncodeNullableString(lease?.consumerId?.value),
            appleQueueEncodeNullableLong(lease?.acquiredAt?.epochMilliseconds),
            appleQueueEncodeNullableLong(lease?.expiresAt?.epochMilliseconds),
            appleQueueEncodeNullableString(error?.code?.value),
            error?.category?.name ?: APPLE_QUEUE_NULL_MARKER,
            error?.severity?.name ?: APPLE_QUEUE_NULL_MARKER,
            error?.recoverability?.name ?: APPLE_QUEUE_NULL_MARKER,
            appleQueueEncodeNullableString(error?.message),
            appleQueueEncodeMetadata(entry.metadata),
        )
        if (includeStrategyDecision) {
            val decision = entry.strategyDecision
            fields += listOf(
                appleQueueEncodeNullableString(decision?.decisionId?.value),
                appleQueueEncodeNullableString(decision?.planId?.value),
                appleQueueEncodeNullableString(decision?.requestedStrategy?.name),
                appleQueueEncodeNullableString(decision?.effectiveProfileId?.value),
                appleQueueEncodeNullableString(decision?.effectiveStrategy?.name),
                appleQueueEncodeNullableLong(decision?.configurationVersion?.value),
                appleQueueEncodeNullableString(decision?.disposition?.name),
            )
        }
        return fields.joinToString("\t")
    }

    private fun decodeEntry(
        line: String,
        includeStrategyDecision: Boolean,
    ): QueueEntry {
        val fields = line.split('\t')
        val expectedFieldCount = if (includeStrategyDecision) {
            CURRENT_ENTRY_FIELD_COUNT
        } else {
            LEGACY_ENTRY_FIELD_COUNT
        }
        require(fields.size == expectedFieldCount)
        val executionContext = ExecutionContext(
            executionId = ExecutionId(appleQueueHexDecode(fields[6])),
            correlationId = CorrelationId(appleQueueHexDecode(fields[7])),
            traceId = appleQueueDecodeNullableString(fields[8])?.let(::TraceId),
            requestId = appleQueueDecodeNullableString(fields[9])?.let(::RequestId),
            tenantId = appleQueueDecodeNullableString(fields[10])?.let(::TenantId),
            userId = appleQueueDecodeNullableString(fields[11])?.let(::UserId),
            localeTag = appleQueueDecodeNullableString(fields[12])?.let(::LocaleTag),
            runtimeVersion = appleQueueDecodeNullableString(fields[13])?.let(::RuntimeVersion),
            configurationVersion = appleQueueDecodeNullableString(fields[14])
                ?.let(::ConfigurationVersion),
            metadata = appleQueueDecodeMetadata(fields[15]),
        )
        val synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId(appleQueueHexDecode(fields[1])),
            sessionId = SynchronizationSessionId(appleQueueHexDecode(fields[2])),
            direction = SynchronizationDirection.valueOf(fields[3]),
            mode = SynchronizationMode.valueOf(fields[4]),
            priority = WorkflowPriority.valueOf(fields[5]),
            context = executionContext,
        )

        val retryAttempt = fields[19].appleQueueToNullableInt()?.let(::RetryAttempt)
        val retryBudgetColumns = listOf(fields[20], fields[21], fields[22])
            .map { it.appleQueueToNullableLong() }
        check(retryBudgetColumns.all { it == null } || retryBudgetColumns.all { it != null })
        val retryBudgetState = if (retryBudgetColumns.all { it == null }) {
            null
        } else {
            RetryBudgetState(
                windowStartedAt = DataLoomInstant(checkNotNull(retryBudgetColumns[0])),
                lastEvaluatedAt = DataLoomInstant(checkNotNull(retryBudgetColumns[1])),
                cumulativeDelay = SchedulingDelay(checkNotNull(retryBudgetColumns[2])),
            )
        }

        val workflowColumns = listOf(fields[23], fields[24])
            .map { it.appleQueueToNullableLong() }
        check(workflowColumns.all { it == null } || workflowColumns.all { it != null })
        val workflowTimeoutState = if (workflowColumns.all { it == null }) {
            null
        } else {
            WorkflowTimeoutState(
                startedAt = DataLoomInstant(checkNotNull(workflowColumns[0])),
                deadline = DataLoomInstant(checkNotNull(workflowColumns[1])),
            )
        }

        val leaseStrings = listOf(fields[25], fields[26]).map(::appleQueueDecodeNullableString)
        val leaseInstants = listOf(fields[27], fields[28]).map { it.appleQueueToNullableLong() }
        val leaseColumns = leaseStrings + leaseInstants
        check(leaseColumns.all { it == null } || leaseColumns.all { it != null })
        val lease = if (leaseColumns.all { it == null }) {
            null
        } else {
            QueueLease(
                id = QueueLeaseId(checkNotNull(leaseStrings[0])),
                consumerId = QueueConsumerId(checkNotNull(leaseStrings[1])),
                acquiredAt = DataLoomInstant(checkNotNull(leaseInstants[0])),
                expiresAt = DataLoomInstant(checkNotNull(leaseInstants[1])),
            )
        }

        val errorCode = appleQueueDecodeNullableString(fields[29])
        val errorCategory = if (fields[30] == APPLE_QUEUE_NULL_MARKER) null else fields[30]
        val errorSeverity = if (fields[31] == APPLE_QUEUE_NULL_MARKER) null else fields[31]
        val errorRecoverability = if (fields[32] == APPLE_QUEUE_NULL_MARKER) null else fields[32]
        val errorMessage = appleQueueDecodeNullableString(fields[33])
        val errorColumns = listOf(
            errorCode,
            errorCategory,
            errorSeverity,
            errorRecoverability,
            errorMessage,
        )
        check(errorColumns.all { it == null } || errorColumns.all { it != null })
        val lastError = if (errorColumns.all { it == null }) {
            null
        } else {
            ApplePersistedQueueError(
                code = ErrorCode(checkNotNull(errorCode)),
                category = ErrorCategory.valueOf(checkNotNull(errorCategory)),
                severity = ErrorSeverity.valueOf(checkNotNull(errorSeverity)),
                recoverability = Recoverability.valueOf(checkNotNull(errorRecoverability)),
                message = checkNotNull(errorMessage),
            )
        }

        val strategyDecision = if (!includeStrategyDecision) {
            null
        } else {
            val strategyColumns: List<Any?> = listOf(
                appleQueueDecodeNullableString(fields[35]),
                appleQueueDecodeNullableString(fields[36]),
                appleQueueDecodeNullableString(fields[37]),
                appleQueueDecodeNullableString(fields[38]),
                appleQueueDecodeNullableString(fields[39]),
                fields[40].appleQueueToNullableLong(),
                appleQueueDecodeNullableString(fields[41]),
            )
            check(strategyColumns.all { it == null } || strategyColumns.all { it != null })
            if (strategyColumns.all { it == null }) {
                null
            } else {
                PersistedStrategyDecision(
                    decisionId = StrategyDecisionId(checkNotNull(strategyColumns[0]) as String),
                    planId = StrategyPlanId(checkNotNull(strategyColumns[1]) as String),
                    requestedStrategy = BuiltInSynchronizationStrategy.valueOf(
                        checkNotNull(strategyColumns[2]) as String,
                    ),
                    effectiveProfileId = StrategyProfileId(
                        checkNotNull(strategyColumns[3]) as String,
                    ),
                    effectiveStrategy = BuiltInSynchronizationStrategy.valueOf(
                        checkNotNull(strategyColumns[4]) as String,
                    ),
                    configurationVersion = StrategyConfigurationVersion(
                        checkNotNull(strategyColumns[5]) as Long,
                    ),
                    disposition = StrategyDisposition.valueOf(
                        checkNotNull(strategyColumns[6]) as String,
                    ),
                )
            }
        }

        return QueueEntry(
            id = QueueEntryId(appleQueueHexDecode(fields[0])),
            synchronizationRequest = synchronizationRequest,
            state = QueueEntryState.valueOf(fields[16]),
            enqueuedAt = DataLoomInstant(fields[17].appleQueueToLongStrict()),
            availableAt = DataLoomInstant(fields[18].appleQueueToLongStrict()),
            retryAttempt = retryAttempt,
            lease = lease,
            lastError = lastError,
            metadata = appleQueueDecodeMetadata(fields[34]),
            retryBudgetState = retryBudgetState,
            workflowTimeoutState = workflowTimeoutState,
            strategyDecision = strategyDecision,
        )
    }
}
''',
)

# ---------------------------------------------------------------------------
# Focused contract, submission, in-memory, Android, Apple, and consumer tests
# ---------------------------------------------------------------------------
write(
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/strategy/PersistedStrategyDecisionTest.kt",
    r'''package io.dataloom.api.strategy

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PersistedStrategyDecisionTest {

    @Test
    fun effectiveStrategyMustBeConcrete() {
        assertFailsWith<IllegalArgumentException> {
            decision(effective = BuiltInSynchronizationStrategy.ADAPTIVE)
        }
    }

    @Test
    fun rejectedDecisionCannotBecomeDurableWork() {
        assertFailsWith<IllegalArgumentException> {
            decision(disposition = StrategyDisposition.REJECT)
        }
    }

    private fun decision(
        effective: BuiltInSynchronizationStrategy =
            BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        disposition: StrategyDisposition = StrategyDisposition.DEFER,
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("profile-1"),
        effectiveStrategy = effective,
        configurationVersion = StrategyConfigurationVersion(4L),
        disposition = disposition,
    )
}
''',
)

write(
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/submission/QueueSubmissionStrategyDecisionPreflightTest.kt",
    r'''package io.dataloom.runtime.submission

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.SynchronizationProviderBindings
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
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import kotlin.test.Test
import kotlin.test.assertIs

class QueueSubmissionStrategyDecisionPreflightTest {

    @Test
    fun matchingStrategyDecisionIsAccepted() {
        val decision = decision()
        val submission = submission(decision)
        val preflight = QueueSubmissionPreflight(encoder(entry(decision)))

        assertIs<QueueSubmissionPreflightResult.Ready>(preflight.prepare(submission))
    }

    @Test
    fun changedStrategyDecisionIsRejectedBeforeProviderPolicy() {
        val submission = submission(decision(version = 3L))
        val preflight = QueueSubmissionPreflight(encoder(entry(decision(version = 4L))))

        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            preflight.prepare(submission),
        )
    }

    @Test
    fun encoderCannotDropStrategyDecision() {
        val submission = submission(decision())
        val preflight = QueueSubmissionPreflight(encoder(entry(null)))

        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            preflight.prepare(submission),
        )
    }

    @Test
    fun encoderCannotInventStrategyDecision() {
        val submission = submission(null)
        val preflight = QueueSubmissionPreflight(encoder(entry(decision())))

        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            preflight.prepare(submission),
        )
    }

    private fun encoder(entry: QueueEntry): QueuedSynchronizationWorkEncoder =
        QueuedSynchronizationWorkEncoder {
            QueuedSynchronizationWorkEncodingResult.Encoded(QueueEnqueueRequest(entry))
        }

    private fun submission(
        decision: PersistedStrategyDecision?,
    ): QueuedSynchronizationSubmission = QueuedSynchronizationSubmission(
        queueEntryId = QueueEntryId("entry-1"),
        work = QueuedSynchronizationWork(
            request = request(),
            bindings = SynchronizationProviderBindings(
                storageProviderId = ProviderId("storage-1"),
                transportProviderId = ProviderId("transport-1"),
            ),
            strategyDecision = decision,
        ),
        availableAt = DataLoomInstant(1_000L),
    )

    private fun entry(decision: PersistedStrategyDecision?): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
        synchronizationRequest = request(),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        strategyDecision = decision,
    )

    private fun decision(version: Long = 3L): PersistedStrategyDecision =
        PersistedStrategyDecision(
            decisionId = StrategyDecisionId("decision-1"),
            planId = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId("offline-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(version),
            disposition = StrategyDisposition.DEFER,
        )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    )
}
''',
)

write(
    "dataloom-testing/src/commonTest/kotlin/io/dataloom/testing/queue/StrategyDecisionInMemoryQueueProviderTest.kt",
    r'''package io.dataloom.testing.queue

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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StrategyDecisionInMemoryQueueProviderTest {

    @Test
    fun decisionSurvivesRetryDeferralAndExpiredLeaseRecovery() = runTest {
        val provider = InMemoryQueueProvider()
        val expected = decision()
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.enqueue(QueueEnqueueRequest(entry(expected))),
        )

        val first = acquire(provider, 2_000L, "lease-1")
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

        val retried = acquire(provider, 3_000L, "lease-2")
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

        val deferred = acquire(provider, 4_000L, "lease-3", expiresAt = 5_000L)
        assertEquals(expected, deferred.strategyDecision)
        assertIs<ProviderOperationResult.Success<io.dataloom.api.queue.ExpiredLeaseRecoveryResult>>(
            provider.recoverExpiredLeases(
                ExpiredLeaseRecoveryRequest(DataLoomInstant(5_001L)),
            ),
        )

        assertEquals(expected, acquire(provider, 6_000L, "lease-4").strategyDecision)
    }

    private suspend fun acquire(
        provider: InMemoryQueueProvider,
        now: Long,
        leaseId: String,
        expiresAt: Long = now + 1_000L,
    ): QueueEntry {
        val result = assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(
            provider.acquire(
                QueueAcquireRequest(
                    consumerId = QueueConsumerId("consumer-1"),
                    leaseId = QueueLeaseId(leaseId),
                    acquiredAt = DataLoomInstant(now),
                    leaseExpiresAt = DataLoomInstant(expiresAt),
                    maxEntries = 1,
                ),
            ),
        ).value
        return assertIs<QueueAcquireResult.Entries>(result).entries.single()
    }

    private fun entry(decision: PersistedStrategyDecision): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
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
        configurationVersion = StrategyConfigurationVersion(8L),
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
}
''',
)

write(
    "dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/StrategyDecisionRoomPersistenceInstrumentedTest.kt",
    r'''package io.dataloom.queue.room

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
''',
)

write(
    "dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/queue/AppleStrategyDecisionQueuePersistenceTest.kt",
    r'''package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppleStrategyDecisionQueuePersistenceTest {

    @Test
    fun versionThreeSnapshotPreservesExactStrategyDecision() {
        val expected = decision()
        val original = entry(expected)
        val encoded = AppleQueueStateFileCodec.encodeSnapshot(
            AppleQueueSnapshot(entries = linkedMapOf(original.id.value to original)),
        )

        val decoded = AppleQueueStateFileCodec.decodeSnapshot(encoded)
            .entries.getValue(original.id.value)

        assertEquals(expected, decoded.strategyDecision)
    }

    @Test
    fun versionTwoSnapshotRemainsReadableWithoutInventingDecision() {
        val original = entry(null)
        val versionOne = AppleQueueStateFileCodec.encode(
            mapOf(original.id.value to original),
        )
        val entryLine = versionOne.lineSequence().drop(1).first { it.isNotEmpty() }
        val versionTwo = "DATALOOM_QUEUE_STATE\t2\nE\t$entryLine\n"

        val decoded = AppleQueueStateFileCodec.decodeSnapshot(versionTwo)
            .entries.getValue(original.id.value)

        assertNull(decoded.strategyDecision)
    }

    @Test
    fun partiallyPopulatedStrategyDecisionFailsClosed() {
        val presentEntry = entry(decision())
        val absentEntry = entry(null)
        val present = AppleQueueStateFileCodec.encodeSnapshot(
            AppleQueueSnapshot(entries = linkedMapOf(presentEntry.id.value to presentEntry)),
        ).split('\n').toMutableList()
        val absent = AppleQueueStateFileCodec.encodeSnapshot(
            AppleQueueSnapshot(entries = linkedMapOf(absentEntry.id.value to absentEntry)),
        ).split('\n')

        val presentFields = present[1].split('\t').toMutableList()
        val absentFields = absent[1].split('\t')
        presentFields[37] = absentFields[37]
        present[1] = presentFields.joinToString("\t")

        assertFailsWith<AppleQueueMalformedStateException> {
            AppleQueueStateFileCodec.decodeSnapshot(present.joinToString("\n"))
        }
    }

    private fun entry(decision: PersistedStrategyDecision?): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
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
        configurationVersion = StrategyConfigurationVersion(9L),
        disposition = StrategyDisposition.DEFER,
    )
}
''',
)

write(
    "runtime-external-consumer/src/commonMain/kotlin/io/dataloom/consumer/DurableStrategyDecisionExternalConsumerProbe.kt",
    r'''package io.dataloom.consumer

import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.runtime.queue.QueuedSynchronizationWork

/** External-consumer probe for durable strategy-decision queue evidence. */
public object DurableStrategyDecisionExternalConsumerProbe {

    public fun fromEntry(entry: QueueEntry): PersistedStrategyDecision? =
        entry.strategyDecision

    public fun fromWork(work: QueuedSynchronizationWork): PersistedStrategyDecision? =
        work.strategyDecision
}
''',
)

# ---------------------------------------------------------------------------
# Documentation
# ---------------------------------------------------------------------------
append_section(
    "docs/api/queue-model.md",
    "## Durable strategy decision",
    r'''## Durable strategy decision

`QueueEntry.strategyDecision` optionally carries the bounded
`PersistedStrategyDecision` accepted before durable admission. It stores only
stable decision, plan, profile, strategy, configuration-version, and disposition
identity. Queue providers must preserve it across acquisition, retry, non-retry
deferral, lease recovery, process reconstruction, and migrations. Legacy and
non-strategy queue entries retain `null` rather than receiving an invented plan.''',
)
append_section(
    "docs/api/queue-submission.md",
    "## Strategy-decision correspondence",
    r'''## Strategy-decision correspondence

When `QueuedSynchronizationWork.strategyDecision` is non-null, the
application-owned encoder must place the exact same value in
`QueueEnqueueRequest.entry.strategyDecision`. Submission preflight rejects a
changed, dropped, or invented decision before timeout, circuit, or queue-provider
policy executes. This prevents configuration changes or encoder behavior from
silently changing an already accepted strategy.''',
)
append_section(
    "docs/android/room-queue-provider.md",
    "## Schema version 7 strategy decision",
    r'''## Schema version 7 strategy decision

Room schema version 7 adds seven nullable columns containing the bounded
strategy-decision identity. `MIGRATION_6_7` preserves every existing queue,
retry, circuit, and administration row and leaves the new columns null for
legacy work. Partially populated decision columns are corrupt durable state and
fail closed; no default strategy is inferred.''',
)
append_section(
    "docs/apple/queue-state-store.md",
    "## Queue snapshot version 3",
    r'''## Queue snapshot version 3

The current Apple queue snapshot is version 3. It appends the bounded strategy
decision to each queue entry while retaining strict reads for entry-only version
1 and entry-plus-receipt version 2 snapshots. The next successful write upgrades
a historical snapshot atomically. A partially populated decision group is
rejected as corrupt state, and historical entries remain explicitly unplanned
rather than receiving current configuration.''',
)

write(
    "docs/audits/DL-039B-durable-strategy-decision-persistence-checkpoint.md",
    r'''# DL-039B durable strategy decision persistence checkpoint

## Scope

This slice connects the accepted strategy decision to durable queue work and
preserves that bounded identity in the in-memory, Android Room, and Apple queue
stores. It follows the fail-closed admission boundary merged in PR #163.

## Accepted invariants

- Effective strategy is concrete; `ADAPTIVE` may be requested but is never the
  persisted effective strategy.
- A rejected decision cannot be persisted as durable work.
- Submission preflight rejects changed, dropped, or invented strategy identity
  before queue-provider, timeout, or circuit policy.
- Queue transitions preserve decision ID, plan ID, requested strategy,
  effective profile, effective strategy, configuration version, and disposition.
- Android schema 7 migrates legacy rows with all decision columns null.
- Apple snapshot version 3 reads versions 1 and 2 without inventing a decision.
- Partial durable decision groups fail closed on Android and Apple.
- Diagnostics expose only decision presence, never dynamic identifiers,
  payloads, metadata, credentials, exception text, or provider values.

## Evidence

Focused common tests cover decision invariants, submission correspondence, and
in-memory retry/deferral/recovery preservation. Android managed-device tests
cover close/reopen plus retry, deferral, and expired-lease recovery. Migration
coverage validates 6 to 7 without data invention. iOS Simulator tests cover
version-3 round trip, version-2 backward read, and corrupt partial decision
rejection. Exact JVM and Kotlin/Native ABI declarations, Room schema evidence,
external-consumer compilation, XCFramework/header validation, and Swift smoke
compilation remain permanent PR gates.

## Remaining DL-039B work

This does not complete offline-first or the six-strategy engine. Queued execution
must next require the persisted decision, resolve or reconstruct the immutable
accepted plan without current-policy re-evaluation, and prove that retry,
restart, lease recovery, configuration rollout/rollback, conflict handling, and
events cannot silently alter it. Cache-first, hybrid, adaptive execution, full
platform reference flows, and the complete acceptance matrix remain open.
''',
)

print("DL-039B durable strategy decision patch applied.")
