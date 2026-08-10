# Cache-first strategy execution

## Status

**Bounded first slice.** `CacheFirstStrategyExecutor` handles every branch
`BuiltInSynchronizationStrategyEvaluator` can produce for
`CacheFirstStrategyProfile` that is directly, synchronously executable. The
branch that requires durable queue admission for a scheduled refresh
(`requireDurableRefresh = true`, the profile default) is explicitly rejected
rather than silently misexecuted — no coordinator in this codebase today
wires an evaluated plan into durable queue admission, so honoring that branch
correctly is out of scope for this executor. See "Known gap" below.

## What it does

`CacheFirstStrategyProfile` produces four kinds of evaluated plan, and this
executor handles all four:

| Scenario | Plan shape | Executor behavior |
|---|---|---|
| Cache FRESH/STALE, no refresh requested | `operations = [SERVE_LOCAL]` | Reports the served cache state; no provider besides the local-fallback capability is touched. |
| Cache FRESH/STALE, refresh requested with `requireDurableRefresh = false` | `operations = [SERVE_LOCAL, PULL_REMOTE, PERSIST_REMOTE]` | Reports the served cache state, then synchronously runs the registered `PULL` `SynchronizationPipeline` and attaches its result. |
| Cache MISSING, connectivity available | `operations = remoteOperations(direction, persistRemote = true)`, no `SERVE_LOCAL` | Pure provider-backed pipeline execution for the request's direction — no local serve attempted. |
| PUSH, connectivity available | `operations = [READ_LOCAL, PUSH_REMOTE]` | Provider-backed `PUSH` pipeline execution. |

All other scenarios (`STALE` + `REJECT` policy, `MISSING` cache with no
connectivity, unknown cache state, `PUSH` with no connectivity) are rejected
or deferred by the evaluator itself before this executor is ever invoked.

## The `ServedFromCache` result

Serving local cache state as the *primary, expected* outcome is not the same
thing as `FallbackActivated` (which means a remote attempt failed and local
state was substituted afterward). `StrategySynchronizationExecutionResult.ServedFromCache`
is a distinct terminal state for that reason:

```kotlin
public data class ServedFromCache(
    override val evaluation: StrategyEvaluationResult,
    override val completedAt: DataLoomInstant,
    public val cacheState: StrategyCacheState,       // FRESH or STALE
    public val refreshOutput: StrategyTransportOutput? = null,
) : StrategySynchronizationExecutionResult
```

`refreshOutput` is non-null only when a synchronous, non-durable refresh ran
alongside serving local state. Its presence does not change `cacheState`,
which always describes what was actually served to evidence at admission
time — not the outcome of the refresh that may have run afterward.

## Local-state consistency

Cache-first reuses `StrategyLocalFallbackProvider` (the same typed,
payload-free capability remote-first uses for its fallback branch) to serve
local state — no new provider capability was introduced. A resolved storage
provider that does not implement `StrategyLocalFallbackProvider` is rejected
with `LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED` before any operation runs.

If the fallback provider reports no available local state (`Unavailable`)
in a scenario the evaluator only reaches when its own evidence already
asserted `FRESH` or `STALE`, that is a genuine runtime inconsistency between
admission-time evidence and the actual storage read — not a normal branch.
It fails closed with a distinct contract error
(`DL-STRATEGY-CACHE-FIRST-LOCAL-STATE-MISMATCH`) rather than being folded
into an ordinary rejection or silently treated as success.

## Known gap: durable/scheduled refresh

`CacheFirstStrategyProfile.requireDurableRefresh` defaults to `true`. When
true, the evaluator's `refreshOperations()` produces
`[ENQUEUE_DURABLE_WORK, SCHEDULE_REFRESH]` instead of the synchronous
`[PULL_REMOTE, PERSIST_REMOTE]` pair — meaning the *default* profile
configuration for a refresh-on-hit policy hits this gap today.

`StrategyQueueAdmissionEvaluator` and `StrategyDurableContinuationPlan`
already exist in this codebase as building blocks for durable-work admission,
but nothing currently wires an evaluated plan containing
`ENQUEUE_DURABLE_WORK` into that machinery before reaching strategy
execution. `CacheFirstStrategyExecutor` detects this case explicitly
(`ENQUEUE_DURABLE_WORK in evaluation.plan.operations`) and rejects it with
`StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED` — a
distinct, honest reason, not a reuse of the generic `UNSUPPORTED_PLAN`.

**To get synchronous cache-first refresh behavior today**, construct
`CacheFirstStrategyProfile` with `requireDurableRefresh = false`. Durable
scheduled refresh requires the queue-admission wiring described above, which
is a separate, larger piece of work — not a variant of this executor.

## Coordinator wiring

`StrategySynchronizationExecutionCoordinator` requires
`StrategyOperationInput.ProviderBacked` for `CACHE_FIRST` (the same
requirement as `REMOTE_FIRST` — cache-first always works through resolved
storage/transport providers, never a caller-supplied direct `ChangeSet`).

## Provider-protected (circuit-breaker) execution

`ProviderProtectedStrategyExecutionBoundary` wraps whichever storage/transport
providers a plan actually resolves, generically — it is not strategy-specific.
Cache-first plans therefore receive the same transport/storage circuit
protection as network-only and remote-first automatically once a protection
spec is configured. This has not yet been exercised by a dedicated test at
the `DataLoomBuilderProtectedStrategyTest` coordinator-protection layer;
that is a candidate for follow-up hardening, the same shape as the
network-only/remote-first coverage already shipped for `#101`/`#102`.
