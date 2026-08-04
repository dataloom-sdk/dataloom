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
            f"Expected exactly one match in {path}, found {count}: {old[:100]!r}",
        )
    write(path, content.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Bound every durable strategy identifier and redact default diagnostics.
# ---------------------------------------------------------------------------
identity_path = (
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/"
    "StrategyIdentity.kt"
)
replace_once(
    identity_path,
    "import kotlin.jvm.JvmInline\n",
    """import kotlin.jvm.JvmInline

private const val MAXIMUM_STRATEGY_IDENTIFIER_LENGTH: Int = 256
""",
)
for type_name in (
    "StrategyProfileId",
    "StrategyDecisionId",
    "StrategyPlanId",
):
    replace_once(
        identity_path,
        f'''        require(value.isNotBlank()) {{ "{type_name} value must not be blank." }}
''',
        f'''        require(value.isNotBlank()) {{ "{type_name} value must not be blank." }}
        require(value.length <= MAXIMUM_STRATEGY_IDENTIFIER_LENGTH) {{
            "{type_name} value must not exceed " +
                "$MAXIMUM_STRATEGY_IDENTIFIER_LENGTH characters."
        }}
''',
    )

replace_once(
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/StrategyEvaluation.kt",
    '''        require(disposition != StrategyDisposition.REJECT) {
            "Rejected strategy decisions must not be persisted as durable work."
        }
    }
}
''',
    '''        require(disposition != StrategyDisposition.REJECT) {
            "Rejected strategy decisions must not be persisted as durable work."
        }
    }

    /** Bounded diagnostic output that excludes all dynamic strategy identifiers. */
    override fun toString(): String =
        "PersistedStrategyDecision(" +
            "requestedStrategy=$requestedStrategy, " +
            "effectiveStrategy=$effectiveStrategy, " +
            "configurationVersion=${configurationVersion.value}, " +
            "disposition=$disposition)"
}
''',
)

replace_once(
    "dataloom-api/src/commonMain/kotlin/io/dataloom/api/queue/QueueEntry.kt",
    '''        require(retryBudgetState == null || retryAttempt != null) {
            "QueueEntry retryBudgetState requires a non-null retryAttempt."
        }
    }
}
''',
    '''        require(retryBudgetState == null || retryAttempt != null) {
            "QueueEntry retryBudgetState requires a non-null retryAttempt."
        }
    }

    /** Bounded diagnostic output that excludes identifiers, metadata, and errors. */
    override fun toString(): String =
        "QueueEntry(" +
            "state=$state, " +
            "direction=${synchronizationRequest.direction}, " +
            "mode=${synchronizationRequest.mode}, " +
            "hasRetryAttempt=${retryAttempt != null}, " +
            "hasRetryBudgetState=${retryBudgetState != null}, " +
            "hasLease=${lease != null}, " +
            "hasLastError=${lastError != null}, " +
            "metadataEntryCount=${metadata.entries.size}, " +
            "hasWorkflowTimeoutState=${workflowTimeoutState != null}, " +
            "hasStrategyDecision=${strategyDecision != null})"
}
''',
)

write(
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/strategy/"
    "StrategyIdentifierBoundsTest.kt",
    r'''package io.dataloom.api.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrategyIdentifierBoundsTest {

    @Test
    fun identifiersAcceptTheMaximumBound() {
        val value = "s".repeat(256)

        assertEquals(value, StrategyProfileId(value).value)
        assertEquals(value, StrategyDecisionId(value).value)
        assertEquals(value, StrategyPlanId(value).value)
    }

    @Test
    fun identifiersRejectValuesBeyondTheMaximumBound() {
        val value = "s".repeat(257)

        assertFailsWith<IllegalArgumentException> { StrategyProfileId(value) }
        assertFailsWith<IllegalArgumentException> { StrategyDecisionId(value) }
        assertFailsWith<IllegalArgumentException> { StrategyPlanId(value) }
    }
}
''',
)

write(
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/strategy/"
    "PersistedStrategyDecisionDiagnosticsTest.kt",
    r'''package io.dataloom.api.strategy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistedStrategyDecisionDiagnosticsTest {

    @Test
    fun toStringExcludesDynamicIdentifiers() {
        val decision = PersistedStrategyDecision(
            decisionId = StrategyDecisionId("sensitive-decision"),
            planId = StrategyPlanId("sensitive-plan"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId("sensitive-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(8L),
            disposition = StrategyDisposition.DEFER,
        )

        val diagnostic = decision.toString()

        assertFalse("sensitive-decision" in diagnostic)
        assertFalse("sensitive-plan" in diagnostic)
        assertFalse("sensitive-profile" in diagnostic)
        assertTrue("effectiveStrategy=OFFLINE_FIRST" in diagnostic)
        assertTrue("disposition=DEFER" in diagnostic)
    }
}
''',
)

write(
    "dataloom-api/src/commonTest/kotlin/io/dataloom/api/queue/"
    "QueueEntryDiagnosticsTest.kt",
    r'''package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueEntryDiagnosticsTest {

    @Test
    fun toStringExcludesEntryContextMetadataAndStrategyIdentifiers() {
        val entry = QueueEntry(
            id = QueueEntryId("sensitive-entry"),
            synchronizationRequest = SynchronizationRequest(
                workflowId = WorkflowId("sensitive-workflow"),
                sessionId = SynchronizationSessionId("sensitive-session"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("sensitive-execution"),
                    correlationId = CorrelationId("sensitive-correlation"),
                ),
            ),
            state = QueueEntryState.PENDING,
            enqueuedAt = DataLoomInstant(1_000L),
            availableAt = DataLoomInstant(1_000L),
            metadata = DataLoomMetadata.of(
                mapOf("authorization" to "sensitive-metadata-value"),
            ),
            strategyDecision = PersistedStrategyDecision(
                decisionId = StrategyDecisionId("sensitive-decision"),
                planId = StrategyPlanId("sensitive-plan"),
                requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
                effectiveProfileId = StrategyProfileId("sensitive-profile"),
                effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
                configurationVersion = StrategyConfigurationVersion(3L),
                disposition = StrategyDisposition.DEFER,
            ),
        )

        val diagnostic = entry.toString()

        listOf(
            "sensitive-entry",
            "sensitive-workflow",
            "sensitive-session",
            "sensitive-execution",
            "sensitive-correlation",
            "sensitive-metadata-value",
            "sensitive-decision",
            "sensitive-plan",
            "sensitive-profile",
        ).forEach { value -> assertFalse(value in diagnostic) }
        assertTrue("metadataEntryCount=1" in diagnostic)
        assertTrue("hasStrategyDecision=true" in diagnostic)
    }
}
''',
)

# ---------------------------------------------------------------------------
# Preserve existing Apple migration expectations while advancing current v3.
# ---------------------------------------------------------------------------
replace_once(
    "dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/retry/"
    "AppleFileRetryAdministrationExecutorTest.kt",
    '        assertTrue(content.startsWith("DATALOOM_QUEUE_STATE\\t2\\n"))\n',
    '        assertTrue(content.startsWith("DATALOOM_QUEUE_STATE\\t3\\n"))\n',
)

# ---------------------------------------------------------------------------
# Android corruption proof: a partial strategy group never reaches execution.
# ---------------------------------------------------------------------------
write(
    "dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/"
    "StrategyDecisionRoomCorruptionInstrumentedTest.kt",
    r'''package io.dataloom.queue.room

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
''',
)

# ---------------------------------------------------------------------------
# Reconcile canonical docs instead of leaving stale v2/schema-6 statements.
# ---------------------------------------------------------------------------
android_doc = "docs/android/room-queue-provider.md"
replace_once(android_doc, "| Schema version | `6` |\n", "| Schema version | `7` |\n")
replace_once(
    android_doc,
    "| Committed schema | `dataloom-queue-room/schemas/"
    "io.dataloom.queue.room.internal.DataLoomRoomDatabase/6.json` |\n",
    "| Committed schema | `dataloom-queue-room/schemas/"
    "io.dataloom.queue.room.internal.DataLoomRoomDatabase/7.json` |\n",
)
replace_once(
    android_doc,
    """`MIGRATION_5_6` adds durable circuit-administration command, authorization,
result, and redacted failure evidence without rewriting queue, circuit, or retry
administration rows.

Instrumented migration tests validate every adjacent migration through version
6, preserve representative queue and circuit rows, verify each new table, and
reopen the current database through the production migration set.
""",
    """`MIGRATION_5_6` adds durable circuit-administration command, authorization,
result, and redacted failure evidence without rewriting queue, circuit, or retry
administration rows. `MIGRATION_6_7` appends seven nullable strategy-decision
columns to `queue_entries`; legacy rows remain null and are never assigned the
current strategy configuration.

Instrumented migration tests validate every adjacent migration through version
7, preserve representative queue and circuit rows, verify each new table and
strategy column group, and reopen the current database through the production
migration set.
""",
)
replace_once(
    android_doc,
    "Queue requests, metadata,\nsanitized error fields, and bounded retry timing evidence are persisted.",
    "Queue requests, metadata, sanitized error fields, bounded retry timing,\n"
    "workflow deadlines, and bounded strategy-decision identity are persisted.",
)

apple_doc = "docs/apple/queue-state-store.md"
replace_once(
    apple_doc,
    "for queue entries, retry attempts, retry budgets, availability, leases, and\n"
    "immutable workflow deadlines.",
    "for queue entries, retry attempts, retry budgets, availability, leases,\n"
    "immutable workflow deadlines, and bounded strategy-decision identity.",
)
replace_once(
    apple_doc,
    "- immutable workflow start and absolute deadline;\n",
    "- immutable workflow start and absolute deadline;\n"
    "- bounded strategy decision, plan, profile, configuration, and disposition identity;\n",
)
replace_once(
    apple_doc,
    """The format has a versioned header and exactly 35 fields per entry. On every read,
the provider validates:
""",
    """The current version-3 format has exactly 42 fields per entry. Historical
version-1 and version-2 entries retain their original 35-field layout and remain
strictly readable. On every read, the provider validates:
""",
)
replace_once(
    apple_doc,
    "- complete-or-null retry budget, workflow deadline, lease, and error groups;\n",
    "- complete-or-null retry budget, workflow deadline, lease, error, and strategy groups;\n",
)
replace_once(
    apple_doc,
    "- format migration for a future snapshot revision; and\n",
    "- queued immutable-plan reconstruction and replay from the persisted decision; and\n",
)

strategy_doc = "docs/strategies/README.md"
replace_once(
    strategy_doc,
    "durable-decision contracts plus a deterministic planner for all six\n",
    "durable-decision contracts and queue persistence plus a deterministic planner for all six\n",
)
replace_once(
    strategy_doc,
    "| [Adaptive](./adaptive.md) | A bounded policy must select deterministically from approved concrete strategies. | Deterministic allowlisted selection implemented; durable admission pending |\n",
    "| [Adaptive](./adaptive.md) | A bounded policy must select deterministically from approved concrete strategies. | Deterministic allowlisted selection and durable decision identity implemented; immutable plan replay pending |\n",
)
replace_once(
    strategy_doc,
    """- `PersistedStrategyDecision` defines the non-sensitive identity durable work
  must retain across retry, lease recovery, and restart.
""",
    """- `PersistedStrategyDecision` defines the bounded non-sensitive identity that
  in-memory, Android Room, and Apple queue stores preserve across retry,
  non-retry deferral, lease recovery, reopen, and restart.
""",
)

readme = "README.md"
replace_once(
    readme,
    """| 2 | [DL-039B six strategy engine](https://github.com/dataloom-sdk/dataloom/issues/102) | IN PROGRESS | Versioned contracts and deterministic planner for all six strategies; direct network-only and remote-first vertical slices | Complete offline-first, cache-first, hybrid, and adaptive runtimes; persist effective decisions; qualify the full connectivity/cache/fallback/retry/conflict/restart matrix without silent strategy changes |
""",
    """| 2 | [DL-039B six strategy engine](https://github.com/dataloom-sdk/dataloom/issues/102) | IN PROGRESS | Versioned contracts and deterministic planner for all six strategies; direct network-only and remote-first vertical slices; fail-closed queue admission and durable strategy-decision identity across in-memory, Room, and Apple queues | Complete offline-first, cache-first, hybrid, and adaptive runtimes; persist/replay the immutable accepted execution plan without current-policy re-evaluation; qualify the full connectivity/cache/fallback/retry/conflict/restart matrix without silent strategy changes |
""",
)

checkpoint = "docs/audits/DL-039B-durable-strategy-decision-persistence-checkpoint.md"
replace_once(
    checkpoint,
    """- Diagnostics expose only decision presence, never dynamic identifiers,
  payloads, metadata, credentials, exception text, or provider values.
""",
    """- Strategy profile, decision, and plan identifiers are capped at 256 characters.
- `PersistedStrategyDecision`, `QueueEntry`, and queued-work diagnostics exclude
  dynamic strategy identifiers, payloads, metadata, credentials, exception
  text, and provider values.
""",
)

print("DL-039B follow-up hardening patch applied.")
