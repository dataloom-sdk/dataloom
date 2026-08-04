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
- Unsupported, extra, missing, or operation-inconsistent capability sets reject
  before provider resolution.
- Replay accepts only finite direction-specific operation sequences that match
  the executor actually invoked.
- Local serving and fallback require persisted cache-state evidence; no current
  or invented evidence is used.
- Protected failure classes and cancellation cannot be converted into local
  fallback.
- Provider-backed PUSH/PULL/BIDIRECTIONAL reuse canonical pipelines.
- Non-persisting remote pull remains transport-only.
- Typed fallback uses only the persisted fallback allowlist and evaluated cache
  state.
- `RECONCILE` uses the optional narrow storage-owned
  `StrategyReconciliationProvider`; it does not require an otherwise unused
  transport binding, and it has independent circuit/timeout protection.
- Direct and protected facade overloads expose the capability additively.
- Ordinary and circuit-aware queue workers route plan-bearing work through the
  accepted coordinator; protected queue execution preserves ordered protection
  evidence.
- Entries without a complete plan retain the historical execution path.
- A retry evaluator inconsistency for known failed work is terminal and can
  never become queue completion.
- A pipeline that skips before provider effects contributes no fabricated
  completed-operation evidence and does not trigger reconciliation.

## Persistence evidence

The same reviewed tree includes Room schema 8 and Apple queue format 4. Complete
plans survive migration, reopen, retry, non-retry deferral, and expired-lease
recovery. Malformed frames fail closed.

## Qualification evidence

The accepted-plan replay tree passed the combined common/JVM, Kotlin/Native,
iOS simulator, Android compilation, external-consumer, ABI, XCFramework, and
repository-hygiene lane before its one-time qualification helpers and failure
evidence were removed. This checkpoint-only follow-up does not change runtime,
API, persistence, schema, or test sources; it provides a repository-owner head
for the standard pull-request, Android, and Apple validation workflows.

## Remaining strategy acceptance

Application-owned atomic local-intent/outbox admission, cache-value and refresh
ownership, hybrid conflict/coherence application, durable strategy event
coverage, and complete native Android/KMP Android/KMP iOS reference matrices
remain separate gates.
