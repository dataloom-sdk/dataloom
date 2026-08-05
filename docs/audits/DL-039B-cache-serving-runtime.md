# DL-039B direct cache-first serving runtime checkpoint

## Accepted in this slice

The common runtime invokes `StrategyCacheAccessProvider` for the bounded
cache-first plan whose exact operation list is `SERVE_LOCAL`, disposition is
`EXECUTE`, origin is `LOCAL`, and required capabilities are `STORAGE` plus
`CACHE_ACCESS`.

The runtime constructs a payload-free `StrategyCacheAccessRequest` from the
immutable evaluated decision, effective concrete profile, configuration
version, original synchronization request, and evaluated cache state. It never
returns application domain values; the application continues to read those
through its own repository after DataLoom confirms that local synchronized state
may be exposed.

Provider-observed results are represented explicitly:

- `CacheServed` reports `LOCAL` origin, the evaluated cache state, and exact
  provider freshness evidence;
- an evaluated stale state may safely improve to provider-observed fresh state;
- an evaluated fresh state that becomes stale returns `CacheUnavailable` with
  `FRESHNESS_DOWNGRADED` and does not silently serve, re-evaluate, or switch to
  remote;
- missing, unknown, or otherwise unavailable provider state returns
  `CacheUnavailable` with `PROVIDER_REPORTED_UNAVAILABLE`; and
- provider failure remains `Failed` with `transportAttempted=false`.

Direct cache serving performs no ordinary storage read/apply/checkpoint call,
transport call, queue mutation, scheduler call, implicit retry, or current-policy
re-evaluation. Adaptive selection is supported when it deterministically chooses
a concrete cache-first profile.

Protected direct cache verification is implemented through the independent
`strategy.evaluate-cache-access` timeout/circuit boundary documented in
[DL-039B protected cache access](./DL-039B-protected-cache-access.md). It does not
reuse generic storage circuit state and adds no third-party dependency.

## Fail-closed boundary

This slice intentionally supports only cache-only local serving. Plans that
promise refresh, execute a cache-miss remote branch, push outbound work, or use a
durable-queue trigger remain rejected before cache-provider invocation. This
prevents the runtime from reporting refresh or remote work that it has not
actually owned and committed.

The same rule applies to every non-offline-first `DEFER` plan. Only the
offline-first path has an atomic local-intent/outbox admission boundary. A
cache-first, remote-first, hybrid, or adaptive-selected direct request therefore
returns `UNSUPPORTED_PLAN` instead of `Deferred` until its queue or scheduler
admission has actually committed.

## Executable evidence

Focused common tests cover:

- fresh local serving with provider-observed freshness;
- policy-allowed stale serving;
- stale-to-fresh improvement;
- fresh-to-stale drift rejection;
- provider-reported missing state;
- provider failure without transport evidence;
- adaptive selection preserving concrete cache-first execution;
- refresh-plan rejection before provider invocation;
- protected cache access, typed unavailability, timeout, and open-circuit
  behavior;
- fail-closed non-atomic direct deferral across cache-first, remote-first, and
  hybrid policies; and
- rejection of direct cache-serving results at the durable queue mapper.

External-consumer compilation exercises the public cache-access and protection
surfaces plus freshness/origin metadata. JVM and Kotlin/Native ABI baselines,
common tests, Android regression, and Apple/XCFramework validation remain
mandatory on each immutable pull-request head.

## Remaining integration

Issue #102 remains open. The next cache-first work must implement:

1. synchronous refresh and cache-miss remote execution;
2. durable refresh admission, deduplication, scheduling, retry/circuit state,
   and restart recovery;
3. remote persistence, freshness/checkpoint update, and conflict-safe coherence;
4. durable cache decision and refresh events/diagnostics; and
5. the full PUSH, PULL, BIDIRECTIONAL, FULL, DELTA, failure, cancellation,
   restart, native Android, KMP Android, and KMP iOS matrix under #101.
