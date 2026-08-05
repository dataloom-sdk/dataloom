# DL-039B protected cache-access checkpoint

## Accepted in this slice

The plan-aware provider-protection boundary now preserves the dedicated
`StrategyCacheAccessProvider` contract for the bounded direct cache-first plan
whose exact operation list is `SERVE_LOCAL`, disposition is `EXECUTE`, origin is
`LOCAL`, and required capabilities are `STORAGE` plus `CACHE_ACCESS`.

Protected cache verification has an independent public configuration:

- `DataLoomStrategyCacheAccessProtectionSpec`;
- stable operation identity `strategy.evaluate-cache-access`;
- dedicated circuit state store and provider/operation scope;
- optional cooperative provider timeout; and
- `StrategyCacheAccessCircuitBreakerFailureClassifier`.

Generic storage protection remains separately required for the resolved storage
role. It is assembled but no ordinary storage operation runs on the cache-only
path. Cache verification is invoked at most once after its own circuit
permission and produces one ordered `ProviderProtectionOperationEvidence`
entry.

## Result and circuit semantics

- Provider `Available` remains a circuit success and returns the existing
  `CacheServed` result with local origin and provider-observed freshness.
- Provider `Unavailable` remains a circuit success and returns the existing
  typed `CacheUnavailable` result; a semantic miss does not degrade dependency
  health.
- Provider failure preserves the canonical error and circuit classification.
- Provider timeout returns `STRATEGY_CACHE_ACCESS_PROVIDER_TIMEOUT`, records a
  circuit failure, preserves `transportAttempted=false`, and performs no remote
  fallback.
- An open circuit rejects before cache-provider invocation with the canonical
  `PROVIDER_CIRCUIT_OPEN` failure and bounded pre-execution evidence.
- Missing cache-access protection or an incompatible scope rejects before any
  provider operation.

No provider value, domain payload, credential, header, checkpoint content, or
arbitrary metadata enters protection evidence.

## Executable evidence

Focused common tests cover:

- protected fresh-cache serving and exact operation evidence;
- typed cache unavailability remaining circuit success;
- missing cache-access protection rejecting before invocation;
- open-circuit rejection before provider invocation;
- cooperative provider timeout recorded as circuit failure; and
- rejection of an unrelated operation-bearing circuit scope.

The external-consumer fixture compiles the new public protection spec and stable
operation identity without internal dependencies.

## Deliberate fail-closed boundaries

This checkpoint does not implement cache refresh, cache-miss remote execution,
durable cache work, or accepted-plan cache replay. It does not protect queue,
scheduler, or connectivity roles and does not combine cache access with local
fallback or reconciliation in one current plan. An unexpected plan requiring
those optional storage extensions simultaneously fails closed rather than
silently dropping an interface.

## Remaining #102 work

1. Implement inline refresh and cache-miss remote execution with exact source,
   persistence, partial-failure, and cancellation semantics.
2. Implement durable refresh admission, deduplication, scheduling, retry/circuit
   state, scheduler-failure recovery, and process relaunch.
3. Add conflict-safe cache persistence/coherence and invalidation behavior.
4. Add durable cache decision/refresh events, operations state, health, and
   support diagnostics.
5. Qualify PUSH, PULL, BIDIRECTIONAL, FULL, DELTA, failure, cancellation,
   restart, native Android, KMP Android, and KMP iOS behavior under #101.

Issues #102 and #101 remain open.
