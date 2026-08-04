# DL-039B persisted accepted-plan execution checkpoint

## Scope

This checkpoint executes a plan that was already accepted and persisted. The
runtime receives the synchronization request, durable decision, complete plan,
and explicit provider bindings. It does not receive a strategy profile or
current `StrategyRuntimeEvidence`.

## Execution guarantees

- Exact decision/plan/request correspondence is validated before provider
  resolution.
- Provider roles are derived only from the durable continuation.
- Unsupported capabilities reject before provider invocation.
- Provider-backed PUSH/PULL/BIDIRECTIONAL reuse canonical pipelines.
- Non-persisting remote pull remains transport-only.
- Typed fallback uses only the persisted fallback allowlist and evaluated cache
  state.
- `RECONCILE` uses the optional narrow `StrategyReconciliationProvider` and has
  independent circuit/timeout protection.
- Direct and protected facade overloads expose the capability additively.
- Ordinary and circuit-aware queue workers route plan-bearing work through the
  accepted coordinator; protected queue execution preserves ordered protection
  evidence.
- Entries without a complete plan retain the historical execution path.

## Persistence evidence

The same reviewed tree includes Room schema 8 and Apple queue format 4. Complete
plans survive migration, reopen, retry, non-retry deferral, and expired-lease
recovery. Malformed frames fail closed.

## Remaining strategy acceptance

Application-owned atomic local-intent/outbox admission, cache-value and refresh
ownership, hybrid conflict/coherence application, durable strategy event
coverage, and complete native Android/KMP Android/KMP iOS reference matrices
remain separate gates.
