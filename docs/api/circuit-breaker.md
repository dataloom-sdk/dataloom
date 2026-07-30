# Circuit-breaker contracts and state machine

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Explicit scope, durable state contracts,
> atomic compare-and-set persistence, deterministic closed/open/half-open
> transitions, and one controlled half-open probe are implemented. Production
> Android/iOS stores, retry-path integration, operations, and observability remain.

## Scope

Each circuit uses one explicit `CircuitBreakerScope`. DataLoom does not silently
inherit or fall back between scopes.

Supported scope shapes are:

- global;
- provider;
- provider plus retry operation;
- tenant plus provider plus retry operation; and
- workflow.

A host may configure more than one circuit, but the integration must define and
document how those independently scoped circuits are evaluated. This slice does
not invent implicit precedence.

## Durable state

`CircuitBreakerState` persists only bounded operational evidence:

- phase: `CLOSED`, `OPEN`, or `HALF_OPEN`;
- closed-window consecutive failure count and start time;
- open deadline;
- half-open probe generation and in-flight marker; and
- last update time.

It contains no payload, credentials, headers, exception messages, provider
instances, or arbitrary metadata.

## Persistence boundary

`CircuitBreakerStateStore` exposes:

- `load(scope)`; and
- atomic `compareAndSet(request)`.

A null expected version means the record must not already exist. Implementations
must return a conflict rather than overwriting a newer state. Cancellation must
propagate. Room, SQLDelight, and native persistence types must not leak through
the public contract.

## Deterministic transitions

`CircuitBreakerCoordinator` applies these rules:

1. A missing or closed circuit allows ordinary execution.
2. Eligible failures are counted inside the configured failure window.
3. Reaching the threshold opens the circuit until `openDuration` expires.
4. An open circuit rejects execution and returns its retry instant.
5. At the exact open deadline, one compare-and-set winner enters `HALF_OPEN` and
   receives a `CircuitBreakerProbePermit`.
6. Other contenders observe the persisted in-flight probe and are rejected.
7. A successful matching probe closes the circuit.
8. A failed matching probe reopens the circuit for a new duration.
9. A stale probe permit cannot mutate a later generation or recovered state.
10. Persisted clock regression is rejected fail-closed.

The failure-window boundary is inclusive. A failure at exactly the configured
window remains part of that window; a later failure starts a new count.

## Concurrency and restart behavior

Every state mutation uses compare-and-set with a bounded retry loop. This avoids
lost updates when failures or probe acquisition race. Persisted open and
half-open state is loaded again after process recreation; no in-memory sequence
is required for recovery.

The current repository includes an in-test store proving the state-machine and
compare-and-set semantics. A production durable platform store is the next
persistence slice and remains mandatory before V1.

## Remaining V1 work

- Android Room and KMP iOS durable store implementations;
- integration before retry/provider execution;
- canonical circuit events, metrics, logs, and trace fields;
- authorized manual open/close/reset operations with audit;
- failure classification mapping into circuit outcomes;
- process-death, multi-process, and high-contention platform qualification; and
- Book 2 AC-FUNC-004 end-to-end recovery evidence.
