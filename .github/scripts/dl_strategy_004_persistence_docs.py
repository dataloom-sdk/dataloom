from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, content: str) -> None:
    (ROOT / path).write_text(content.rstrip() + "\n")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"Expected one persistence-doc match in {path}, found {count}: {old[:120]!r}")
    write(path, content.replace(old, new, 1))


retry_test = (
    "dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/retry/"
    "AppleFileRetryAdministrationExecutorTest.kt"
)
replace_once(
    retry_test,
    '        assertTrue(content.startsWith("DATALOOM_QUEUE_STATE\\t3\\n"))\n',
    '        assertTrue(content.startsWith("DATALOOM_QUEUE_STATE\\t4\\n"))\n',
)

room_doc = "docs/android/room-queue-provider.md"
replace_once(room_doc, "| Schema version | `7` |", "| Schema version | `8` |")
replace_once(
    room_doc,
    "| Committed schema | `dataloom-queue-room/schemas/io.dataloom.queue.room.internal.DataLoomRoomDatabase/7.json` |",
    "| Committed schema | `dataloom-queue-room/schemas/io.dataloom.queue.room.internal.DataLoomRoomDatabase/8.json` |",
)
replace_once(
    room_doc,
    """administration rows. `MIGRATION_6_7` appends seven nullable strategy-decision
columns to `queue_entries`; legacy rows remain null and are never assigned the
current strategy configuration.

Instrumented migration tests validate every adjacent migration through version
7, preserve representative queue and circuit rows, verify each new table and
strategy column group, and reopen the current database through the production
migration set.
""",
    """administration rows. `MIGRATION_6_7` appends seven nullable strategy-decision
columns to `queue_entries`; legacy rows remain null and are never assigned the
current strategy configuration. `MIGRATION_7_8` adds one nullable bounded
`strategy_plan_snapshot`; identity-only version-7 rows remain explicitly
unplanned and no current policy is evaluated during migration.

Instrumented migration tests validate every adjacent migration through version
8, preserve representative queue and circuit rows, verify each new table and
strategy field, and reopen the current database through the production
migration set. Complete accepted plans survive reopen, retry, non-retry
deferral, and expired-lease recovery; malformed plan frames fail closed.
""",
)
replace_once(
    room_doc,
    "workflow deadlines, and bounded strategy-decision identity are persisted.",
    "workflow deadlines, bounded strategy-decision identity, and the bounded immutable accepted-plan frame are persisted.",
)

apple_doc = "docs/apple/queue-state-store.md"
replace_once(
    apple_doc,
    "immutable workflow deadlines, and bounded strategy-decision identity. It does not complete Apple background",
    "immutable workflow deadlines, bounded strategy-decision identity, and complete immutable accepted-plan frames. It does not complete Apple background",
)
replace_once(
    apple_doc,
    "- bounded strategy decision, plan, profile, configuration, and disposition identity;",
    "- bounded strategy decision identity plus the complete immutable accepted plan and durable continuation;",
)
replace_once(
    apple_doc,
    """The current version-3 format has exactly 42 fields per entry. Historical
version-1 and version-2 entries retain their original 35-field layout and remain
strictly readable. On every read, the provider validates:
""",
    """The current version-4 format has exactly 43 fields per entry. Version-3
strategy-decision entries retain their 42-field layout, while historical
version-1 and version-2 entries retain the original 35-field layout. All remain
strictly readable. On every read, the provider validates:
""",
)
replace_once(
    apple_doc,
    "- complete-or-null retry budget, workflow deadline, lease, error, and strategy groups;",
    "- complete-or-null retry budget, workflow deadline, lease, error, strategy identity, and accepted-plan frames;",
)
replace_once(
    apple_doc,
    "- queued immutable-plan reconstruction and replay from the persisted decision; and",
    "- queued immutable-plan execution through the accepted continuation without policy re-evaluation; and",
)
replace_once(
    apple_doc,
    """## Queue snapshot version 3

The current Apple queue snapshot is version 3. It appends the bounded strategy
decision to each queue entry while retaining strict reads for entry-only version
1 and entry-plus-receipt version 2 snapshots. The next successful write upgrades
a historical snapshot atomically. A partially populated decision group is
rejected as corrupt state, and historical entries remain explicitly unplanned
rather than receiving current configuration.
""",
    """## Queue snapshot version 4

The current Apple queue snapshot is version 4. It appends the bounded encoded
complete accepted plan after the version-3 strategy-decision fields while
retaining strict reads for entry-only version 1, entry-plus-receipt version 2,
and identity-only version 3 snapshots. The next successful write upgrades a
historical snapshot atomically. A malformed plan or decision/plan mismatch is
rejected as corrupt state. Historical identity-only entries remain explicitly
unplanned rather than receiving current configuration.
""",
)

queue_doc = "docs/api/queue-model.md"
content = read(queue_doc).rstrip()
content += """

## Immutable accepted strategy plan

Strategy queue work may carry both `PersistedStrategyDecision` and the complete
`StrategyExecutionPlan`. The plan contains the original durable continuation
selected before admission. Android Room schema 8 and Apple queue format 4
preserve its bounded deterministic codec frame through retry, deferral, reopen,
lease recovery, and migration.

Legacy identity-only work remains readable with a null plan. New plan-bearing
work fails closed when the snapshot is malformed or no longer corresponds to
the durable decision, request direction, or transfer mode.
"""
write(queue_doc, content)

write(
    "docs/audits/DL-039B-immutable-plan-persistence-checkpoint.md",
    """# DL-039B immutable accepted-plan persistence checkpoint

## Android

Room schema version 8 adds one nullable `strategy_plan_snapshot` text column.
Migration 7 to 8 preserves legacy decision identity and leaves the plan null.
No current profile or runtime evidence is evaluated during migration. The
generated schema, migration, restart, retry, deferral, expired-lease recovery,
and corruption tests form the Android evidence.

## Apple

Queue format version 4 appends one nullable encoded complete-plan field.
Versions 1, 2, and 3 remain readable. Version-3 identity-only work remains null
rather than receiving a current plan. Successful writes upgrade to version 4.
Malformed frames fail as sanitized Apple queue-state integrity failures.

## Common

The in-memory provider preserves the exact plan across the same transition
matrix. Queue encoders and resolvers must preserve value equality for both
decision and plan. Platform persistence performs no strategy evaluation.
""",
)

print("Reconciled Room and Apple immutable-plan persistence documentation.")
