# Offline-first strategy execution

## Status

**Bounded first slice.** `OfflineFirstStrategyExecutor` handles every branch
`BuiltInSynchronizationStrategyEvaluator` can produce for
`OfflineFirstStrategyProfile` that is directly, synchronously executable. The
branch that requires durable queue admission (`requireDurableQueue = true`,
the profile default) is explicitly rejected rather than silently
misexecuted — no coordinator in this codebase today wires an evaluated plan
into durable queue admission, so honoring that branch correctly is out of
scope for this executor. See "Known gap" below.

## What it does

Unlike `CacheFirstStrategyExecutor`, `BuiltInSynchronizationStrategyEvaluator`
only ever produces an `EXECUTE`-disposition plan for
`OfflineFirstStrategyProfile` when connectivity is available
(`StrategyConnectivity.AVAILABLE`) — when connectivity is unavailable or
unknown, the plan is `DEFER`, which `StrategySynchronizationExecutionCoordinator`
already handles generically before any executor is invoked. So every plan
this executor receives always carries at least one remote operation
(`PUSH_REMOTE`/`PULL_REMOTE`); there is no local-only `EXECUTE` branch to
handle here.

| Scenario | Plan shape (excerpt) | Executor behavior |
|---|---|---|
| PUSH | `operations ⊇ [ACCEPT_LOCAL, READ_LOCAL, PUSH_REMOTE]` | `ACCEPT_LOCAL` requires no action of its own — it documents that the local write was already accepted by the time the plan was evaluated, the whole point of offline-first. The executor runs the registered `PUSH` `SynchronizationPipeline`. |
| PULL, cache FRESH/STALE | `operations ⊇ [SERVE_LOCAL, PULL_REMOTE, PERSIST_REMOTE]` | Serves local state via `StrategyLocalFallbackProvider` first, then runs the registered `PULL` pipeline. Unlike cache-first, the served cache state is not part of the terminal result — the synchronized pipeline outcome is. |
| PULL, cache MISSING | `operations ⊇ [PULL_REMOTE, PERSIST_REMOTE]`, no `SERVE_LOCAL` | Pure provider-backed pipeline execution — no local serve attempted. |
| BIDIRECTIONAL | `operations ⊇ [ACCEPT_LOCAL, (SERVE_LOCAL), READ_LOCAL, PUSH_REMOTE, PULL_REMOTE, PERSIST_REMOTE]` | Combines the PUSH and PULL behaviors above, then runs the registered `BIDIRECTIONAL` pipeline. |
| Any direction, `reconcileWhenOnline = true` (the default) | `operations` also includes `RECONCILE` | See "Reconciliation" below. |

All other scenarios (connectivity unavailable/unknown) are deferred by the
evaluator itself before this executor is ever invoked.

## Local-state consistency

Offline-first reuses `StrategyLocalFallbackProvider` (the same typed,
payload-free capability cache-first and remote-first already use) to serve
local state — no new provider capability was introduced. A resolved storage
provider that does not implement `StrategyLocalFallbackProvider` is rejected
with `LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED` before any operation runs.

If the fallback provider reports no available local state (`Unavailable`)
in a scenario the evaluator only reaches when its own evidence already
asserted `FRESH` or `STALE`, that is a genuine runtime inconsistency between
admission-time evidence and the actual storage read — not a normal branch.
It fails closed with a distinct contract error
(`DL-STRATEGY-OFFLINE-FIRST-LOCAL-STATE-MISMATCH`) rather than being folded
into an ordinary rejection or silently treated as success.

## Reconciliation

`OfflineFirstStrategyProfile.reconcileWhenOnline` defaults to `true`. When
the evaluated plan includes `RECONCILE` and the pipeline run succeeded
(`StrategySynchronizationExecutionResult.Executed`), the executor invokes
`StrategyReconciliationProvider.reconcileStrategy` — the same narrow,
bounded reconciliation hook `AcceptedStrategyPlanExecutionCoordinator`
already uses for durable-queue-continuation plans. A resolved storage
provider that does not implement `StrategyReconciliationProvider` is
rejected with `RECONCILIATION_PROVIDER_NOT_CONFIGURED`; a reconciliation
failure propagates as `StrategySynchronizationExecutionResult.Failed` with
the provider's own error. Reconciliation is skipped entirely (never
attempted) when the pipeline run itself did not succeed — there is nothing
to reconcile.

## Known gap: durable queue admission

`OfflineFirstStrategyProfile.requireDurableQueue` defaults to `true`. When
true, the evaluated plan's `operations` includes `ENQUEUE_DURABLE_WORK` —
meaning the *default* profile configuration hits this gap today, the same
situation `CacheFirstStrategyExecutor` documents for its own durable-refresh
branch.

`StrategyQueueAdmissionEvaluator` and `StrategyDurableContinuationPlan`
already exist in this codebase as building blocks for durable-work admission,
but nothing currently wires an evaluated plan containing
`ENQUEUE_DURABLE_WORK` into that machinery before reaching strategy
execution. `OfflineFirstStrategyExecutor` detects this case explicitly
(`ENQUEUE_DURABLE_WORK in evaluation.plan.operations`) and rejects it with
`StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED` — reused
rather than duplicated, since the underlying root cause (no queue-admission
wiring) is identical to cache-first's own gap.

**To get synchronous offline-first behavior today**, construct
`OfflineFirstStrategyProfile` with `requireDurableQueue = false`. Durable
queue admission requires the queue-admission wiring described above, which
is a separate, larger piece of work — not a variant of this executor.

## Coordinator wiring

`StrategySynchronizationExecutionCoordinator` requires
`StrategyOperationInput.ProviderBacked` for `OFFLINE_FIRST` (the same
requirement as `REMOTE_FIRST` and `CACHE_FIRST` — offline-first always works
through resolved storage/transport providers, never a caller-supplied direct
`ChangeSet`).

## Provider-protected (circuit-breaker) execution

`ProviderProtectedStrategyExecutionBoundary` wraps whichever storage/transport
providers a plan actually resolves, generically — it is not strategy-specific.
Offline-first plans therefore receive the same transport/storage circuit
protection as the other three built-in strategies automatically once a
protection spec is configured. This has not yet been exercised by a
dedicated test at the `DataLoomBuilderProtectedStrategyTest`
coordinator-protection layer; that is a candidate for follow-up hardening,
the same shape as the network-only/remote-first/cache-first coverage already
shipped for `#101`/`#102`.
