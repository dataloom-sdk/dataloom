# Hybrid strategy execution

## Status

**Bounded first slice.** `HybridStrategyExecutor` handles every branch
`BuiltInSynchronizationStrategyEvaluator` can produce for
`HybridStrategyProfile` that is directly, synchronously executable. The
branch that requires durable queue admission (`reconcileAfterFallback = true`,
the profile default, when the evaluator's `LOCAL` selection is itself a
fallback from a `REMOTE` primary) is explicitly rejected rather than silently
misexecuted, and one narrow transport-free plan shape (`LOCAL` selected for a
PUSH request) is rejected too, since no result type in this codebase
represents a transport-free success yet. See "Known gaps" below.

## What it does

`BuiltInSynchronizationStrategyEvaluator` pre-selects exactly one of
`HybridSource.LOCAL` or `HybridSource.REMOTE` as the effective source for the
whole request, from connectivity/cache evidence at evaluation time. Unlike
`RemoteFirstStrategyExecutor`, this executor never reacts to a runtime remote
failure by improvising a fallback — hybrid's evaluated plan never carries a
`StrategyFallbackPlan`, so whatever source the evaluator selected is simply
attempted; its failure is a plain `Failed` result, not a trigger for a second
local attempt.

| Scenario | Plan shape (excerpt) | Executor behavior |
|---|---|---|
| `REMOTE` selected (primary or as a fallback from a `LOCAL` primary), any direction | `operations ⊇ remoteOperations(direction, persistRemote)` | Runs the registered `SynchronizationPipeline` for the request's direction, honoring `persistRemoteResult` (see below). |
| `LOCAL` selected, PULL | `operations = [SERVE_LOCAL]` | Served via `StrategyLocalFallbackProvider`, same pattern cache-first/offline-first use. Terminal result is `ServedFromCache`, no refresh output — hybrid's `LOCAL` branch never also runs a remote leg. |
| `LOCAL` selected, BIDIRECTIONAL | `operations = [READ_LOCAL, SERVE_LOCAL]` | Same as above — `READ_LOCAL` documents that the push side is locally accepted with no remote push attempted; `SERVE_LOCAL` covers the pull side. |
| `LOCAL` selected, PUSH | `operations = [READ_LOCAL]` | **Rejected** — see "Known gaps" below. |
| `LOCAL` selected as an explicit fallback from a `REMOTE` primary, `reconcileAfterFallback = true` (the default) | `operations` includes `ENQUEUE_DURABLE_WORK` and `RECONCILE` | **Rejected** — see "Known gaps" below. |

## Honoring `persistRemoteResult`

`HybridStrategyProfile.persistRemoteResult` defaults to `true`. The
registered `SynchronizationPipeline` for PULL/BIDIRECTIONAL always persists
remote results — so when a plan's `REMOTE`-selected operations omit
`READ_CHECKPOINT`/`PERSIST_REMOTE` (`persistRemoteResult = false`), this
executor bypasses the pipeline and calls the transport provider directly
instead of silently persisting a result the plan never asked for, the same
way `RemoteFirstStrategyExecutor` does:

- PULL, non-persisting: a direct `TransportProvider.pullChanges` call.
- BIDIRECTIONAL, non-persisting: the registered `PUSH` pipeline (which still
  persists the push side), followed by a direct, non-persisting
  `TransportProvider.pullChanges` call. Terminal output is
  `StrategyTransportOutput.RemoteFirstBidirectional` — a generic type,
  reused here rather than duplicated for hybrid.
- PUSH is unaffected: `persistRemoteResult` only ever gates
  `READ_CHECKPOINT`/`PERSIST_REMOTE`, which apply to the PULL leg only.

## Local-state consistency

Hybrid reuses `StrategyLocalFallbackProvider` (the same typed, payload-free
capability cache-first/offline-first/remote-first already use) to serve
local state — no new provider capability was introduced. A resolved storage
provider that does not implement `StrategyLocalFallbackProvider` is rejected
with `LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED` before any operation runs.

If the fallback provider reports no available local state (`Unavailable`) in
a scenario the evaluator only reaches when its own evidence already asserted
`FRESH` or `STALE`, that is a genuine runtime inconsistency between
admission-time evidence and the actual storage read — not a normal branch.
It fails closed with a distinct contract error
(`DL-STRATEGY-HYBRID-LOCAL-STATE-MISMATCH`) rather than being folded into an
ordinary rejection or silently treated as success.

## Known gaps

### Durable queue admission for the reconciled fallback branch

When `HybridSource.LOCAL` is selected as an explicit fallback from a
`REMOTE` primary (i.e. remote was not eligible, so the evaluator fell back to
local) and `HybridStrategyProfile.reconcileAfterFallback` is `true` (the
default), the evaluated plan's operations include `ENQUEUE_DURABLE_WORK` and
`RECONCILE` alongside the local-serve operations.

`StrategyQueueAdmissionEvaluator` and `StrategyDurableContinuationPlan`
already exist in this codebase as building blocks for durable-work admission,
but nothing currently wires an evaluated plan containing
`ENQUEUE_DURABLE_WORK` into that machinery before reaching strategy
execution — the same gap `CacheFirstStrategyExecutor` and
`OfflineFirstStrategyExecutor` already document for their own durable
branches. `HybridStrategyExecutor` detects this case explicitly
(`ENQUEUE_DURABLE_WORK in evaluation.plan.operations`) and rejects it with
the same shared `StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED`
reason — reused rather than duplicated, since the underlying root cause (no
queue-admission wiring) is identical across all three executors.

`RECONCILE` only ever appears alongside `ENQUEUE_DURABLE_WORK` in the
evaluator's hybrid branch — never alone — so this one check also covers
hybrid's only reachable `RECONCILE` shape. Unlike `OfflineFirstStrategyExecutor`,
this executor never calls `StrategyReconciliationProvider` directly.

**To get synchronous hybrid fallback behavior today**, construct
`HybridStrategyProfile` with `reconcileAfterFallback = false`. Durable queue
admission requires the queue-admission wiring described above, which is a
separate, larger piece of work — not a variant of this executor.

### Transport-free PUSH with `LOCAL` selected

`HybridSource.LOCAL` selected for a PUSH-direction request produces a plan
whose only operation is `READ_LOCAL` — there is no `SERVE_LOCAL` operation to
serve (nothing to serve for a push) and, since `LOCAL` was explicitly chosen
over `REMOTE`, no remote operation either.

No variant of `StrategyTransportOutput` represents a transport-free success,
and unlike the `ACCEPT_LOCAL` no-op branches in `CacheFirstStrategyExecutor`/
`OfflineFirstStrategyExecutor` (always paired with a required remote leg in
the same plan), this is the first genuinely transport-free plan shape in the
strategy engine. Rather than invent a signature-incompatible zero-effort
success value for one narrow branch, it is rejected explicitly with
`StrategyExecutionRejectionReason.HYBRID_LOCAL_PUSH_NOT_YET_SUPPORTED` until
a proper transport-free result type is added to
`StrategySynchronizationExecutionResult`.

## Coordinator wiring

`StrategySynchronizationExecutionCoordinator` requires
`StrategyOperationInput.ProviderBacked` for `HYBRID` (the same requirement as
`REMOTE_FIRST`, `CACHE_FIRST`, and `OFFLINE_FIRST` — hybrid always works
through resolved storage/transport providers, never a caller-supplied direct
`ChangeSet`).

With all five concrete built-in strategies now dispatched, the coordinator's
defensive `UNSUPPORTED_PLAN` fallback branch is currently unreachable via the
public evaluator — `StrategyExecutionPlan.effectiveStrategy` can never be
`ADAPTIVE` itself, and adaptive resolution always produces one of the five
strategies handled above or a `REJECT`-disposition plan that never reaches
the coordinator's `effectiveStrategy` dispatch. The branch is kept as a
defensive fallback for a hypothetical future concrete strategy added without
updating this dispatch; the direct coordinator test that previously exercised
it (`DataLoomBuilderDirectStrategyExecutionTest.strategyWithoutABuiltInExecutorIsUnsupported`)
was removed for this reason rather than repointed a third time.

## Provider-protected (circuit-breaker) execution

`ProviderProtectedStrategyExecutionBoundary` wraps whichever storage/transport
providers a plan actually resolves, generically — it is not strategy-specific.
Hybrid plans therefore receive the same transport/storage circuit protection
as the other four built-in strategies automatically once a protection spec is
configured. This has not yet been exercised by a dedicated test at the
`DataLoomBuilderProtectedStrategyTest` coordinator-protection layer; that is
a candidate for follow-up hardening, the same shape as the
network-only/remote-first/cache-first/offline-first coverage already shipped
for `#101`/`#102`.
