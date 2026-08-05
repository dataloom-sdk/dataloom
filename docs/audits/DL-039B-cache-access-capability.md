# DL-039B cache-access capability and resolution checkpoint

## Accepted in this slice

Cache-first plans that intentionally serve local synchronized state now declare
an explicit `CACHE_ACCESS` provider capability in addition to `STORAGE`.

The deterministic planner adds the capability only when the effective concrete
profile is cache-first and the admitted operation set contains `SERVE_LOCAL`.
This preserves the separate semantics of:

- remote-first and hybrid local fallback, which continue to use
  `StrategyLocalFallbackProvider`; and
- cache-first serving, which requires `StrategyCacheAccessProvider` and
  provider-observed freshness evidence.

Adaptive selection preserves the same rule after it selects a concrete
cache-first profile. Cache misses that select a remote path do not require the
cache-access contract.

`StrategyProviderResolver` validates the selected storage provider before any
provider operation. When `CACHE_ACCESS` is required, a plain `StorageProvider`
fails with `PROVIDER_CONTRACT_MISMATCH`; a `StrategyCacheAccessProvider` resolves
without invocation.

The new capability is encoded by the existing deterministic V1 execution-plan
codec by its stable enum name. Historical frames remain readable, while older
runtimes fail closed if they encounter a capability they do not understand.

## Executable evidence

Focused tests cover:

- fresh cache serving requiring `STORAGE` plus `CACHE_ACCESS`;
- stale-while-refresh retaining cache, queue, and scheduler capabilities;
- cache-miss remote execution not requiring cache access;
- remote-first fallback remaining on the fallback-provider contract;
- adaptive selection of cache-first preserving the capability;
- plain storage rejection; and
- successful capability resolution without provider invocation.

## Deliberate remaining boundary

This checkpoint qualifies deterministic capability planning and provider
contract resolution. It does not invoke cache access or complete cache-first
runtime behavior.

Remaining #102 work includes:

1. Invoke `StrategyCacheAccessProvider` for fresh and allowed-stale plans.
2. Validate provider-observed state against the admitted stale policy and fail
   closed on freshness drift.
3. Return explicit local origin and freshness metadata without exposing domain
   payloads through DataLoom.
4. Implement refresh ownership, deduplication, durable scheduling, retry/circuit
   integration, scheduler-failure recovery, and process restart.
5. Qualify all cache-first direction/mode/platform matrices under #101.

Issue #102 and platform gate #101 remain open.
