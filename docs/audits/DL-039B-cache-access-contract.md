# DL-039B cache-access freshness contract checkpoint

## Accepted in this slice

`StrategyCacheAccessProvider` is the application-owned storage extension used to
verify synchronized local state at the exact cache-serving boundary.

The contract is deliberately payload-free. DataLoom supplies the immutable
strategy decision, plan/profile/configuration identity, the original
synchronization request, the cache state recorded during deterministic policy
evaluation, and whether stale state is admitted. Applications continue to read
actual domain values through their own repositories.

A provider returns either:

- `Available` with `StrategyCacheFreshnessEvidence`; or
- `Unavailable` with a non-serveable cache classification.

Freshness evidence records the provider observation time and an exclusive
`validUntil` deadline. `FRESH` requires observation strictly before the
freshness deadline. Equality is expired and therefore `STALE`. `STALE` requires
observation at or after the deadline.

Requests reject unevaluated, unknown, or missing cache state before a provider
can run. Stale access additionally requires `allowStale=true`. Diagnostics omit
workflow, session, execution, correlation, decision, plan, profile, payload,
and credential data.

## Why a separate contract is required

Cache-first serving is not remote-failure fallback. Reusing
`StrategyLocalFallbackProvider` would incorrectly require a remote outcome and
would blur the distinction between:

- intentionally serving policy-approved cached state; and
- activating local state only after an allowlisted remote failure.

The dedicated boundary lets later runtime work verify that caller-supplied
cache evidence still matches application-owned storage before DataLoom reports a
fresh or permitted-stale hit.

## Executable evidence

Common tests cover:

- fresh and stale request admission;
- rejection of missing or unevaluated cache state;
- stale-policy enforcement;
- exclusive freshness-deadline semantics;
- disjoint available/unavailable results; and
- redaction-safe request diagnostics.

The external-consumer fixture compiles the provider, request, freshness, and
result contracts without internal dependencies.

## Deliberate remaining boundary

This checkpoint freezes the public cache-access contract. It does not yet claim
cache-first runtime completion.

Remaining #102 work includes:

1. Add an explicit plan capability and fail-closed provider resolution for
   cache-serving plans.
2. Invoke the provider for fresh and allowed-stale local-serving decisions and
   return cache-origin/freshness metadata.
3. Reconcile provider-observed freshness drift without silently switching
   strategies.
4. Implement synchronous and durable refresh ownership, deduplication,
   scheduler-failure handling, retry/circuit integration, and restart recovery.
5. Qualify cache-first PUSH, PULL, and BIDIRECTIONAL FULL/DELTA behavior across
   native Android, KMP Android, and KMP iOS.

Issue #102 and platform gate #101 remain open.
