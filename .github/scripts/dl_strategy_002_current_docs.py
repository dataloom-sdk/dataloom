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
        raise SystemExit(
            f"Expected exactly one current-doc match in {path}, found {count}: "
            f"{old[:120]!r}",
        )
    write(path, content.replace(old, new, 1))


strategy_doc = "docs/api/synchronization-strategy.md"
replace_once(
    strategy_doc,
    """## Durable replay

When a plan creates durable work, persist `PersistedStrategyDecision` beside
the encoded work:
""",
    """## Durable replay

When a plan creates durable work, DataLoom carries the accepted
`PersistedStrategyDecision` beside the encoded work:
""",
)
replace_once(
    strategy_doc,
    """Retry, lease recovery, process restart, and platform rescheduling must reuse
that identity. Re-evaluation requires a separate authorized transition; a
provider failure or connectivity change cannot silently select another
strategy.
""",
    """Queue-submission preflight rejects a changed, dropped, or invented decision
before timeout, circuit, or queue-provider policy. In-memory, Android Room, and
Apple file-backed queues preserve the exact identity through retry, non-retry
deferral, lease recovery, reopen, and migration. Legacy work remains explicitly
unplanned (`null`) rather than receiving current configuration.

The next execution gate must reconstruct or load the immutable accepted plan
from this identity. It must not re-evaluate current policy after retry, restart,
or platform rescheduling. An authorized migration is required to replace an
accepted plan.
""",
)
replace_once(
    strategy_doc,
    """The profile contracts, deterministic planner, plan-aware provider resolution,
direct network-only execution, and direct provider-backed remote-first
execution are implemented in common Kotlin and shared by native Android, KMP
Android, and KMP iOS.
""",
    """The profile contracts, deterministic planner, fail-closed durable admission,
plan-aware provider resolution, direct network-only execution, direct provider-
backed remote-first execution, and bounded strategy-decision queue persistence
are implemented in common Kotlin. Room and Apple stores preserve the same
identity used by native Android and KMP platform paths.
""",
)
replace_once(
    strategy_doc,
    """Remote-first durable triggers, cache-first, offline-first, hybrid, and adaptive
runtime execution, durable decision encoding, retry/circuit rescheduling,
conflict persistence, and complete strategy event enrichment remain separate
integration gates. Unsupported plans and triggers are rejected rather than
silently executed through the legacy pipeline.
""",
    """Remote-first durable triggers, offline-first atomic admission/execution,
cache-first, hybrid, and adaptive runtime execution, immutable accepted-plan
reconstruction/replay, conflict application, complete strategy event enrichment,
and full native Android/KMP Android/KMP iOS reference qualification remain
separate integration gates. Unsupported plans and triggers are rejected rather
than silently executed through the legacy pipeline.
""",
)

system_doc = "docs/architecture/system-overview.md"
replace_once(
    system_doc,
    "        retry[Custom retry orchestration]\n",
    "        retry[Retry and circuit engine]\n",
)
replace_once(
    system_doc,
    """- durable queue processing and application-owned queue work encoding;
- custom retry and conflict contracts plus orchestration foundations;
- in-process lifecycle, progress, retry, conflict, and operational event
  dispatch;
- Android connectivity, Room queue, and WorkManager adapters; and
- Kotlin/Native Apple targets plus XCFramework compile validation.
""",
    """- durable queue processing, application-owned work encoding, and bounded
  strategy-decision identity across in-memory, Room, and Apple stores;
- versioned contracts and deterministic planning for all six strategies, plus
  direct network-only and remote-first execution;
- the standard retry/circuit engine, six timeout boundaries, durable Room/Apple
  state, authorized administration, and bounded telemetry foundations;
- custom conflict contracts plus orchestration foundations;
- in-process lifecycle, progress, retry, conflict, and operational event
  dispatch;
- Android connectivity, Room queue, and WorkManager adapters; and
- Kotlin/Native Apple targets, file-backed queue/retry/circuit state,
  XCFramework assembly, header audit, and Swift smoke validation.
""",
)
replace_once(
    system_doc,
    """- a request-level strategy model, evaluator, and persisted execution plan;
- complete semantics and qualification for all six strategies;
- standard exponential backoff, jitter, attempt/time budgets, and durable
  circuit breaking;
- generic built-in conflict policies, durable conflict records, and recovery;
""",
    """- complete offline-first, cache-first, hybrid, and adaptive runtime semantics,
  plus immutable accepted execution-plan reconstruction and replay;
- complete connectivity/cache/fallback/retry/conflict/restart matrices for all
  six strategies;
- real Android and Apple process-termination/relaunch evidence and genuine
  cross-process circuit-probe contention where supported;
- generic built-in conflict policies, durable conflict records, and recovery;
""",
)
replace_once(
    system_doc,
    """The full requirement and evidence matrix is in
[DL-AUDIT-004](../audits/DL-AUDIT-004-v1-production-readiness.md).
""",
    """Use the [audit index](../audits/README.md) for the current conformance record
and the original expanded-V1 requirement baseline.
""",
)

hub = "docs/README.md"
replace_once(
    hub,
    """| Review V1 gaps and release gates | [V1 production-readiness audit](./audits/DL-AUDIT-004-v1-production-readiness.md) |
""",
    """| Review V1 gaps and release gates | [Current V1 conformance audit](./audits/DL-AUDIT-005-current-v1-conformance.md) |
""",
)

checkpoint = "docs/audits/DL-039B-durable-strategy-decision-persistence-checkpoint.md"
checkpoint_content = read(checkpoint).rstrip()
checkpoint_content += """

## Living-document reconciliation

The current strategy API guide and system overview now distinguish accepted
strategy-decision persistence and retry/circuit foundations from the still-
missing immutable plan replay, remaining strategy runtimes, process-loss
qualification, conflict application, and platform reference matrices. Historical
audits remain unchanged and are still labeled as point-in-time evidence.
"""
write(checkpoint, checkpoint_content)

print("Reconciled current strategy and architecture documentation.")
