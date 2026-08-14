# Hybrid strategy execution

## Status

**Complete for every branch the evaluator can produce.** `HybridStrategyExecutor`
now handles every branch `BuiltInSynchronizationStrategyEvaluator` can
produce for `HybridStrategyProfile`, including the transport-free PUSH plan
shape and the durable-admission branch (opt-in via a configured
`QueuedSynchronizationWorkEncoder` — see "Durable queue admission" below).

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
| `LOCAL` selected, PUSH | `operations = [READ_LOCAL]` | Terminal result is `AcceptedLocally` — accepting local intent is genuinely the entire outcome. See "Transport-free PUSH" below. |
| `LOCAL` selected as an explicit fallback from a `REMOTE` primary, `reconcileAfterFallback = true` (the default) | `operations` includes `ENQUEUE_DURABLE_WORK` and `RECONCILE` | Admitted via `StrategyDurableQueueAdmitter` when configured; rejected with the shared `DURABLE_REFRESH_NOT_YET_SUPPORTED` reason otherwise. See "Durable queue admission" below. |

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

## Transport-free PUSH

`HybridSource.LOCAL` selected for a PUSH-direction request without
`ENQUEUE_DURABLE_WORK` (i.e. either not selected as a fallback at all, or
`reconcileAfterFallback = false`) produces a plan whose only operation is
`READ_LOCAL` — there is no `SERVE_LOCAL` operation to serve (nothing to serve
for a push), no `ENQUEUE_DURABLE_WORK` to admit, and, since `LOCAL` was
explicitly chosen over `REMOTE`, no remote operation either.

This executor returns `StrategySynchronizationExecutionResult.AcceptedLocally`
for this shape — accepting local intent (already true by the time this plan
was evaluated) genuinely is the entire outcome, the same `ACCEPT_LOCAL`
no-op meaning `CacheFirstStrategyExecutor`/`OfflineFirstStrategyExecutor`
already use elsewhere, just expressed as a terminal result here since nothing
else in the plan follows it. This was previously rejected with
`StrategyExecutionRejectionReason.HYBRID_LOCAL_PUSH_NOT_YET_SUPPORTED`
(kept as a public enum entry for ABI stability, but no longer produced) until
`AcceptedLocally` was added as the first genuinely transport-free result type
in the strategy engine.

## Durable queue admission for the reconciled fallback branch

When `HybridSource.LOCAL` is selected as an explicit fallback from a
`REMOTE` primary (i.e. remote was not eligible, so the evaluator fell back to
local) and `HybridStrategyProfile.reconcileAfterFallback` is `true` (the
default), the evaluated plan's operations include `ENQUEUE_DURABLE_WORK` and
`RECONCILE` alongside the local-serve operations.

`HybridStrategyExecutor` hands this branch to `StrategyDurableQueueAdmitter`,
an opt-in capability — see
[durable-queue-admission.md](durable-queue-admission.md) for the full
design. Behavior forks on whether `SERVE_LOCAL` is also present:

- **PULL/BIDIRECTIONAL** (`SERVE_LOCAL` present): admission does not replace
  serving local state — both happen. The terminal `ServedFromCache` carries
  a non-null `durableQueueEntryId` when admission succeeds.
- **PUSH** (`SERVE_LOCAL` absent — see the next section): admission *is*
  the entire outcome. Returns `DurablyEnqueued` immediately, the same
  short-circuit shape `OfflineFirstStrategyExecutor` uses for its own
  durable branch.

`RECONCILE` only ever appears alongside `ENQUEUE_DURABLE_WORK` in the
evaluator's hybrid branch — never alone — so admission is the only thing
that needs to happen for it. Unlike `OfflineFirstStrategyExecutor`, this
executor never calls `StrategyReconciliationProvider` directly — the
durably admitted continuation owns `RECONCILE` entirely.

**When no `QueuedSynchronizationWorkEncoder` is configured** (the default),
this executor's behavior is unchanged from before durable admission wiring
existed: the branch is rejected with the same shared
`StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED` reason
cache-first/offline-first use. To get synchronous hybrid fallback behavior
without configuring an encoder at all, construct `HybridStrategyProfile`
with `reconcileAfterFallback = false`.

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
