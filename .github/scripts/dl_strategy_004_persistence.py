from __future__ import annotations

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
        raise SystemExit(
            f"Expected exactly one match in {path}, found {count}: {old[:140]!r}",
        )
    write(path, content.replace(old, new, 1))


# Android Room schema 8.
entity = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/QueueEntryEntity.kt"
replace_once(
    entity,
    " * This entity is part of [DataLoomRoomDatabase] schema version 7.\n",
    " * This entity is part of [DataLoomRoomDatabase] schema version 8.\n",
)
replace_once(
    entity,
    '''    /** Accepted strategy disposition enum name. */
    @ColumnInfo(name = "strategy_disposition")
    val strategyDisposition: String?,
)
''',
    '''    /** Accepted strategy disposition enum name. */
    @ColumnInfo(name = "strategy_disposition")
    val strategyDisposition: String?,

    /** Complete immutable accepted strategy-plan snapshot. */
    @ColumnInfo(name = "strategy_plan_snapshot")
    val strategyPlanSnapshot: String?,
)
''',
)

database = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/DataLoomRoomDatabase.kt"
replace_once(database, "    version = 7,\n", "    version = 8,\n")

migrations = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/DataLoomRoomMigrations.kt"
replace_once(
    migrations,
    '''    /** Complete ordered migration set used by [DataLoomDatabaseBuilder]. */
    public val ALL: Array<Migration>
''',
    '''    /**
     * Adds the nullable complete immutable accepted-plan snapshot.
     *
     * Version-7 strategy work remains readable as identity-only legacy work.
     * Migration never evaluates current policy or invents a current plan.
     */
    public val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN strategy_plan_snapshot TEXT")
        }
    }

    /** Complete ordered migration set used by [DataLoomDatabaseBuilder]. */
    public val ALL: Array<Migration>
''',
)
replace_once(
    migrations,
    '''            MIGRATION_5_6,
            MIGRATION_6_7,
        )
''',
    '''            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        )
''',
)

mappers = "dataloom-queue-room/src/main/kotlin/io/dataloom/queue/room/internal/Mappers.kt"
replace_once(
    mappers,
    "import io.dataloom.api.strategy.StrategyDisposition\n",
    '''import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyExecutionPlanCodec
''',
)
replace_once(
    mappers,
    '''        strategyConfigurationVersion = strategyDecision?.configurationVersion?.value,
        strategyDisposition = strategyDecision?.disposition?.name,
    )
''',
    '''        strategyConfigurationVersion = strategyDecision?.configurationVersion?.value,
        strategyDisposition = strategyDecision?.disposition?.name,
        strategyPlanSnapshot = strategyPlan?.let(StrategyExecutionPlanCodec::encode),
    )
''',
)
replace_once(
    mappers,
    '''    val strategyDecision = if (strategyColumns.all { it == null }) {
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
''',
    '''    val strategyDecision = if (strategyColumns.all { it == null }) {
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
    val strategyPlan = strategyPlanSnapshot?.let(StrategyExecutionPlanCodec::decode)

    return QueueEntry(
''',
)
replace_once(
    mappers,
    '''        workflowTimeoutState = workflowTimeoutState,
        strategyDecision = strategyDecision,
    )
''',
    '''        workflowTimeoutState = workflowTimeoutState,
        strategyDecision = strategyDecision,
        strategyPlan = strategyPlan,
    )
''',
)

# Apple queue format 4.
apple = "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleQueueStateFileCodec.kt"
replace_once(
    apple,
    "import io.dataloom.api.strategy.StrategyDisposition\n",
    '''import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyExecutionPlanCodec
''',
)
replace_once(
    apple,
    '''    private const val LEGACY_HEADER: String = "DATALOOM_QUEUE_STATE\\t1"
    private const val VERSION_TWO_HEADER: String = "DATALOOM_QUEUE_STATE\\t2"
    private const val CURRENT_HEADER: String = "DATALOOM_QUEUE_STATE\\t3"
    private const val ENTRY_PREFIX: String = "E"
    private const val RECEIPT_PREFIX: String = "R"
    private const val LEGACY_ENTRY_FIELD_COUNT: Int = 35
    private const val CURRENT_ENTRY_FIELD_COUNT: Int = 42
''',
    '''    private const val LEGACY_HEADER: String = "DATALOOM_QUEUE_STATE\\t1"
    private const val VERSION_TWO_HEADER: String = "DATALOOM_QUEUE_STATE\\t2"
    private const val VERSION_THREE_HEADER: String = "DATALOOM_QUEUE_STATE\\t3"
    private const val CURRENT_HEADER: String = "DATALOOM_QUEUE_STATE\\t4"
    private const val ENTRY_PREFIX: String = "E"
    private const val RECEIPT_PREFIX: String = "R"
    private const val LEGACY_ENTRY_FIELD_COUNT: Int = 35
    private const val VERSION_THREE_ENTRY_FIELD_COUNT: Int = 42
    private const val CURRENT_ENTRY_FIELD_COUNT: Int = 43
''',
)
replace_once(
    apple,
    "    /** Encodes the current version-3 entry-plus-receipt snapshot. */\n",
    "    /** Encodes the current version-4 entry-plus-receipt-plus-plan snapshot. */\n",
)
replace_once(
    apple,
    "                append(encodeEntry(entry, includeStrategyDecision = true))\n",
    '''                append(
                    encodeEntry(
                        entry = entry,
                        includeStrategyDecision = true,
                        includeStrategyPlan = true,
                    ),
                )
''',
)
replace_once(
    apple,
    "    /** Decodes versions 1, 2, or 3 and reconstructs the complete snapshot. */\n",
    "    /** Decodes versions 1, 2, 3, or 4 and reconstructs the complete snapshot. */\n",
)
replace_once(
    apple,
    '''                LEGACY_HEADER -> decodeLegacyLines(lines)
                VERSION_TWO_HEADER -> decodeVersionTwoLines(lines)
                CURRENT_HEADER -> decodeCurrentLines(lines)
''',
    '''                LEGACY_HEADER -> decodeLegacyLines(lines)
                VERSION_TWO_HEADER -> decodeVersionTwoLines(lines)
                VERSION_THREE_HEADER -> decodeVersionThreeLines(lines)
                CURRENT_HEADER -> decodeCurrentLines(lines)
''',
)
replace_once(
    apple,
    '''                check(entry.strategyDecision == null) {
                    "Version-1 queue snapshots cannot encode a strategy decision."
                }
                append(encodeEntry(entry, includeStrategyDecision = false))
''',
    '''                check(entry.strategyDecision == null) {
                    "Version-1 queue snapshots cannot encode a strategy decision."
                }
                check(entry.strategyPlan == null) {
                    "Version-1 queue snapshots cannot encode a strategy plan."
                }
                append(
                    encodeEntry(
                        entry = entry,
                        includeStrategyDecision = false,
                        includeStrategyPlan = false,
                    ),
                )
''',
)
replace_once(
    apple,
    '''    private fun decodeLegacyLines(lines: List<String>): AppleQueueSnapshot {
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
''',
    '''    private fun decodeLegacyLines(lines: List<String>): AppleQueueSnapshot {
        val entries = linkedMapOf<String, QueueEntry>()
        forEachDataLine(lines) { line ->
            if (entries.size >= APPLE_QUEUE_MAX_ENTRY_COUNT) {
                throw AppleQueueEntryLimitException()
            }
            val entry = decodeEntry(
                line = line,
                includeStrategyDecision = false,
                includeStrategyPlan = false,
            )
            require(entries.put(entry.id.value, entry) == null)
        }
        return AppleQueueSnapshot(entries = entries)
    }
''',
)
replace_once(
    apple,
    '''    private fun decodeVersionTwoLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(lines, includeStrategyDecision = false)

    private fun decodeCurrentLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(lines, includeStrategyDecision = true)

    private fun decodePrefixedLines(
        lines: List<String>,
        includeStrategyDecision: Boolean,
    ): AppleQueueSnapshot {
''',
    '''    private fun decodeVersionTwoLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(
            lines = lines,
            includeStrategyDecision = false,
            includeStrategyPlan = false,
        )

    private fun decodeVersionThreeLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(
            lines = lines,
            includeStrategyDecision = true,
            includeStrategyPlan = false,
        )

    private fun decodeCurrentLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(
            lines = lines,
            includeStrategyDecision = true,
            includeStrategyPlan = true,
        )

    private fun decodePrefixedLines(
        lines: List<String>,
        includeStrategyDecision: Boolean,
        includeStrategyPlan: Boolean,
    ): AppleQueueSnapshot {
''',
)
replace_once(
    apple,
    "                    val entry = decodeEntry(payload, includeStrategyDecision)\n",
    '''                    val entry = decodeEntry(
                        line = payload,
                        includeStrategyDecision = includeStrategyDecision,
                        includeStrategyPlan = includeStrategyPlan,
                    )
''',
)
replace_once(
    apple,
    '''    private fun encodeEntry(
        entry: QueueEntry,
        includeStrategyDecision: Boolean,
    ): String {
''',
    '''    private fun encodeEntry(
        entry: QueueEntry,
        includeStrategyDecision: Boolean,
        includeStrategyPlan: Boolean,
    ): String {
''',
)
replace_once(
    apple,
    '''        if (includeStrategyDecision) {
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
        return fields.joinToString("\\t")
    }

    private fun decodeEntry(
        line: String,
        includeStrategyDecision: Boolean,
    ): QueueEntry {
        val fields = line.split('\\t')
        val expectedFieldCount = if (includeStrategyDecision) {
            CURRENT_ENTRY_FIELD_COUNT
        } else {
            LEGACY_ENTRY_FIELD_COUNT
        }
''',
    '''        if (includeStrategyDecision) {
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
        if (includeStrategyPlan) {
            fields += appleQueueEncodeNullableString(
                entry.strategyPlan?.let(StrategyExecutionPlanCodec::encode),
            )
        }
        return fields.joinToString("\\t")
    }

    private fun decodeEntry(
        line: String,
        includeStrategyDecision: Boolean,
        includeStrategyPlan: Boolean,
    ): QueueEntry {
        val fields = line.split('\\t')
        val expectedFieldCount = when {
            includeStrategyPlan -> CURRENT_ENTRY_FIELD_COUNT
            includeStrategyDecision -> VERSION_THREE_ENTRY_FIELD_COUNT
            else -> LEGACY_ENTRY_FIELD_COUNT
        }
''',
)
replace_once(
    apple,
    '''        return QueueEntry(
            id = QueueEntryId(appleQueueHexDecode(fields[0])),
''',
    '''        val strategyPlan = if (!includeStrategyPlan) {
            null
        } else {
            appleQueueDecodeNullableString(fields[42])
                ?.let(StrategyExecutionPlanCodec::decode)
        }

        return QueueEntry(
            id = QueueEntryId(appleQueueHexDecode(fields[0])),
''',
)
replace_once(
    apple,
    '''            workflowTimeoutState = workflowTimeoutState,
            strategyDecision = strategyDecision,
        )
''',
    '''            workflowTimeoutState = workflowTimeoutState,
            strategyDecision = strategyDecision,
            strategyPlan = strategyPlan,
        )
''',
)

provider = "dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/queue/AppleFileQueueProvider.kt"
replace_once(
    provider,
    ''' * Version-1 entry-only and version-2 entry-plus-receipt snapshots remain
 * readable. Every successful mutation writes the version-3
 * entry-plus-receipt-plus-strategy-decision format and preserves existing
 * administrative retry receipts and bounded strategy identity.
''',
    ''' * Version-1 entry-only, version-2 entry-plus-receipt, and version-3
 * strategy-decision snapshots remain readable. Every successful mutation writes
 * version 4 with the complete immutable accepted strategy plan and preserves
 * existing receipts, bounded strategy identity, and legacy identity-only work.
''',
)

print("Applied Room schema 8 and Apple queue format 4 strategy-plan persistence.")
