# DL-039B direct cache-first local execution checkpoint

## Accepted in this slice

The strategy runtime now executes the bounded cache-first local-serving path for
plans that contain exactly `SERVE_LOCAL`, have disposition `EXECUTE`, require
`CACHE_ACCESS`, use provider-backed input, and promise no refresh side effect.

The runtime resolves the cache-access storage contract, constructs one
payload-free `StrategyCacheAccessRequest`, and invokes
`StrategyCacheAccessProvider.evaluateCacheAccess`. It performs no generic
storage read, transport operation, queue transition, scheduler call, retry, or
fallback.

A verified local hit returns `CacheAvailable` with provider-observed
`StrategyCacheFreshnessEvidence`. DataLoom still does not return domain values;
the application reads them from its own repository after the availability
result.

Provider-observed freshness is authoritative at the serving boundary:

- a stale evaluation may become fresh and remain serveable;
- a fresh evaluation that becomes stale is not silently served; it returns
  `CacheUnavailable` with evaluated `FRESH` and observed `STALE`; and
- missing, unknown, or unevaluated provider state remains a typed
  `CacheUnavailable` result without switching to remote-first or fallback.

Provider failure returns the canonical error with `transportAttempted=false`.
Cancellation propagates unchanged.

## Deliberate fail-closed boundaries

This slice does not execute `SERVE_AND_REFRESH`, `SCHEDULE_REFRESH`, durable
refresh, cache-miss remote operations, or cache-first PUSH. Those plans return
`UNSUPPORTED_PLAN` before cache-provider invocation rather than claiming a
refresh or remote side effect that the runtime did not perform.

The generic provider-protection bridge does not yet preserve the cache-access
extension. If a protection boundary removes that contract, execution fails with
`PROVIDER_PROTECTION_SCOPE_MISMATCH` rather than bypassing protection or calling
an unverified provider.

## Executable evidence

Focused common/JVM/iOS tests cover:

- fresh cache availability and freshness metadata;
- allowed stale serving without refresh;
- stale-to-fresh improvement;
- fresh-to-stale drift without silent serving;
- provider-reported missing cache;
- canonical provider failure;
- cancellation propagation;
- refresh-plan fail-closed behavior;
- input validation; and
- zero generic storage operations.

The external-consumer fixture compiles the new cache availability and
unavailability results without internal dependencies.

## Remaining #102 work

1. Implement refresh ownership, in-flight deduplication, durable scheduling,
   retry/circuit integration, and scheduler-failure recovery.
2. Implement cache-miss remote execution and persistence without silently
   changing the admitted strategy.
3. Add mutation-safe provider protection for cache access.
4. Add durable cache strategy events, health/read-model adoption, and support
   diagnostics.
5. Qualify cache-first PUSH, PULL, and BIDIRECTIONAL FULL/DELTA behavior across
   native Android, KMP Android, and KMP iOS, including process termination and
   relaunch.

Issue #102 and platform gate #101 remain open.
