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
        raise SystemExit(f"Expected one accepted-plan doc match in {path}, found {count}: {old[:140]!r}")
    write(path, content.replace(old, new, 1))


strategy_doc = "docs/api/synchronization-strategy.md"
replace_once(
    strategy_doc,
    """Queue encoders and work resolvers must preserve both the exact decision and the
complete plan. Changed, dropped, or invented plan evidence fails before timeout,
clock, provider, circuit, retry, or coordinator work. Platform stores and the
accepted-plan execution coordinator are the next integration boundary; they
must never re-evaluate current policy after retry, restart, or rescheduling.
""",
    """Queue encoders and work resolvers preserve both the exact decision and the
complete plan. Changed, dropped, or invented plan evidence fails before timeout,
clock, provider, circuit, retry, or coordinator work. Android Room schema 8 and
Apple queue format 4 preserve the bounded plan frame across retry, deferral,
reopen, migration, and lease recovery.

`DataLoom.synchronizeAcceptedPlan(...)` and its protected counterpart execute
only the stored durable continuation. They accept no profile and no current
runtime evidence. Provider roles are resolved from the continuation; typed
fallback uses the stored evaluated cache state, and `RECONCILE` invokes the
narrow optional `StrategyReconciliationProvider`. Plan-bearing queue work is
routed through the same accepted coordinator and never falls back to the legacy
or current-policy strategy coordinator.
""",
)
replace_once(
    strategy_doc,
    """The profile contracts, deterministic planner, fail-closed durable admission,
plan-aware provider resolution, direct network-only execution, direct provider-
backed remote-first execution, bounded strategy-decision queue persistence,
and fail-closed queued resolver correspondence are implemented in common
Kotlin. Room and Apple stores preserve the same
bounded identity; complete native Android, KMP Android, and KMP iOS reference
qualification remains open.
""",
    """The profile contracts, deterministic planner, fail-closed durable admission,
plan-aware provider resolution, direct network-only and remote-first execution,
bounded strategy-decision and complete-plan persistence, resolver
correspondence, and direct/protected/queued accepted-plan execution are
implemented in common Kotlin. Room and Apple preserve the same accepted plan;
complete native Android, KMP Android, and KMP iOS reference qualification
remains open.
""",
)
replace_once(
    strategy_doc,
    """Remote-first durable triggers, offline-first atomic admission/execution,
cache-first, hybrid, and adaptive runtime execution, immutable accepted-plan
reconstruction/replay, conflict application, complete strategy event enrichment,
and full native Android/KMP Android/KMP iOS reference qualification remain
separate integration gates. Unsupported plans and triggers are rejected rather
than silently executed through the legacy pipeline.
""",
    """Accepted-plan replay now covers the frozen storage/transport operations,
typed local fallback, bounded reconciliation, direct protection, and queue
routing without current-policy evaluation. Remaining gates are atomic
application intent/outbox admission, cache value/refresh ownership contracts,
complete hybrid coherence and conflict application, strategy-specific durable
events/diagnostics, and full native Android/KMP Android/KMP iOS reference
qualification. Unsupported capabilities fail closed rather than silently
executing through the legacy pipeline.
""",
)

readme = "README.md"
replace_once(
    readme,
    """Plan-aware provider resolution and direct network-only/remote-first operation
execution are implemented. Bounded strategy-decision identity is preserved by
the in-memory, Android Room, and Apple durable queue stores. Immutable accepted
execution-plan replay, the remaining strategy runtimes, and complete platform
qualification remain required before the strategy engine is complete.
""",
    """Plan-aware provider resolution and direct network-only/remote-first operation
execution are implemented. Bounded strategy decisions and complete immutable
accepted plans survive the in-memory, Android Room, and Apple queues. Direct,
provider-protected, and queued replay execute the frozen continuation without
current-policy evaluation. Atomic application outbox semantics, complete
cache/hybrid/conflict behavior, durable strategy events, and platform reference
qualification remain before the strategy engine is complete.
""",
)
replace_once(
    readme,
    """| 1 | [DL-039B six strategy engine](https://github.com/dataloom-sdk/dataloom/issues/102) | IN PROGRESS | Versioned contracts and deterministic planner for all six strategies; direct network-only and remote-first vertical slices; fail-closed queue admission, durable strategy-decision identity across in-memory, Room, and Apple queues, and queued resolver correspondence before execution | Complete offline-first, cache-first, hybrid, and adaptive runtimes; persist/replay the immutable accepted execution plan without current-policy re-evaluation; qualify the full connectivity/cache/fallback/retry/conflict/restart matrix without silent strategy changes |
""",
    """| 1 | [DL-039B six strategy engine](https://github.com/dataloom-sdk/dataloom/issues/102) | IN PROGRESS | Versioned contracts and deterministic planner for all six strategies; direct network-only and remote-first slices; fail-closed queue admission; Room v8/Apple v4 accepted-plan persistence; exact encoder/resolver correspondence; direct, protected, and queued replay without current-policy evaluation; typed fallback and reconciliation hooks | Complete atomic offline-first intent/outbox admission, cache value/refresh ownership, hybrid coherence/conflict application, durable strategy events/diagnostics, and the full native Android/KMP Android/KMP iOS failure/restart matrix |
""",
)

protected_doc = "docs/api/provider-protected-strategy-execution.md"
content = read(protected_doc).rstrip()
content += """

## Persisted accepted-plan execution

`DataLoomProtectedStrategySynchronization.synchronizeAcceptedPlan(...)` executes
the exact persisted plan and continuation without evaluating a profile or
current runtime evidence. Storage, transport, local fallback, and reconciliation
are independently protected by the configured circuit and provider-timeout
specifications. Missing protection for a resolved required role rejects before
provider invocation.

Plan-bearing protected queue work uses this method directly and returns ordered
provider/circuit evidence in `ProviderProtectedQueueEntryExecutionResult`.
Legacy protected synchronization remains unchanged for entries without an
accepted plan.
"""
write(protected_doc, content)

write(
    "docs/audits/DL-039B-persisted-accepted-plan-execution-checkpoint.md",
    """# DL-039B persisted accepted-plan execution checkpoint

## Scope

This checkpoint executes a plan that was already accepted and persisted. The
runtime receives the synchronization request, durable decision, complete plan,
and explicit provider bindings. It does not receive a strategy profile or
current `StrategyRuntimeEvidence`.

## Execution guarantees

- Exact decision/plan/request correspondence is validated before provider
  resolution.
- Provider roles are derived only from the durable continuation.
- Unsupported capabilities reject before provider invocation.
- Provider-backed PUSH/PULL/BIDIRECTIONAL reuse canonical pipelines.
- Non-persisting remote pull remains transport-only.
- Typed fallback uses only the persisted fallback allowlist and evaluated cache
  state.
- `RECONCILE` uses the optional narrow `StrategyReconciliationProvider` and has
  independent circuit/timeout protection.
- Direct and protected facade overloads expose the capability additively.
- Ordinary and circuit-aware queue workers route plan-bearing work through the
  accepted coordinator; protected queue execution preserves ordered protection
  evidence.
- Entries without a complete plan retain the historical execution path.

## Persistence evidence

The same reviewed tree includes Room schema 8 and Apple queue format 4. Complete
plans survive migration, reopen, retry, non-retry deferral, and expired-lease
recovery. Malformed frames fail closed.

## Remaining strategy acceptance

Application-owned atomic local-intent/outbox admission, cache-value and refresh
ownership, hybrid conflict/coherence application, durable strategy event
coverage, and complete native Android/KMP Android/KMP iOS reference matrices
remain separate gates.
""",
)

print("Reconciled persisted accepted-plan execution documentation.")
