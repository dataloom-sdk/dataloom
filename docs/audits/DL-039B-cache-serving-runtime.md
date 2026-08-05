# DL-039B direct cache-first serving runtime checkpoint

## Accepted in this slice

The common runtime now invokes `StrategyCacheAccessProvider` for the bounded
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

## Fail-closed boundary

This slice intentionally supports only cache-only local serving. Plans that
promise refresh, execute a cache-miss remote branch, push outbound work, or use a
durable-queue trigger remain rejected before cache-provider invocation. This
prevents the runtime from reporting refresh or remote work that it has not
actually owned and committed.

Provider-protected cache access also remains fail-closed. The existing generic
storage bridge does not pretend to implement the dedicated cache-access
extension; an independently scoped timeout/circuit bridge is required before
protected cache serving is accepted.

## Executable evidence

Focused common tests cover:

- fresh local serving with provider-observed freshness;
- policy-allowed stale serving;
- stale-to-fresh improvement;
- fresh-to-stale drift rejection;
- provider-reported missing state;
- provider failure without transport evidence;
- adaptive selection preserving concrete cache-first execution; and
- refresh-plan rejection before provider invocation.

External-consumer compilation exercises the new terminal result variants and
freshness/origin metadata. JVM and Kotlin/Native ABI baselines, common tests,
Android regression, and Apple/XCFramework validation remain mandatory on the
immutable pull-request head.

## Remaining integration

Issue #102 remains open. The next cache-first work must implement:

1. independently protected cache-access timeout/circuit behavior;
2. synchronous refresh and cache-miss remote execution;
3. durable refresh admission, deduplication, scheduling, retry/circuit state,
   and restart recovery;
4. remote persistence, freshness/checkpoint update, and conflict-safe coherence;
5. durable cache decision and refresh events/diagnostics; and
6. the full PUSH, PULL, BIDIRECTIONAL, FULL, DELTA, failure, cancellation,
   restart, native Android, KMP Android, and KMP iOS matrix under #101.
