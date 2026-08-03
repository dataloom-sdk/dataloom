# Circuit-breaker contracts and state machine

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Explicit scope, durable Android/Apple state,
> deterministic closed/open/half-open transitions, controlled probe leases,
> provider/queue/scheduler runtime assembly, common authorized operations
> contracts, and production Android operations persistence/execution are
> implemented. Apple operations execution, complete observability, and
> end-to-end qualification remain.

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
- half-open probe generation, in-flight marker, and exclusive lease deadline; and
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
5. At the exact open deadline, one compare-and-set winner enters `HALF_OPEN`,
   receives a `CircuitBreakerProbePermit`, and persists an exclusive lease.
6. Other contenders observe the persisted in-flight probe and are rejected with
   its lease deadline.
7. At the exact lease deadline, one compare-and-set winner replaces an abandoned
   probe with the next generation and a new lease.
8. A matching probe result at or after its own deadline is rejected as expired.
9. A successful matching, unexpired probe closes the circuit.
10. A failed matching, unexpired probe reopens the circuit for a new duration.
11. A stale probe permit cannot mutate a later generation or recovered state.
12. Persisted clock regression is rejected fail-closed.

The failure-window boundary is inclusive. A failure at exactly the configured
window remains part of that window; a later failure starts a new count.

## Concurrency and restart behavior

Every state mutation uses compare-and-set with a bounded retry loop. This avoids
lost updates when failures or probe acquisition race. Persisted open and
half-open state is loaded again after process recreation. If a process dies or a
caller is cancelled after acquiring the sole probe, its lease eventually expires
and a later compare-and-set winner safely advances the generation.

The repository includes production Android Room and KMP Apple file stores plus
focused restart, compare-and-set, lease-boundary, stale-result, corruption, and
overflow coverage. Storage, transport, queue, scheduler, direct facade, and
selected built-in strategy paths can use explicitly configured circuit
protection without changing historical unprotected entry points.

Common [circuit-administration](./circuit-administration.md) contracts add
deny-by-default, idempotent, durably audited open/close/reset coordination. The
platform command stores and atomic mutation/receipt executors are a separate
remaining slice.

## Remaining V1 work

- production Apple circuit-administration store and atomic executor;
- circuit-administration facade/operations assembly;
- canonical circuit events, metrics, logs, and trace fields;
- process-death, multi-process, and high-contention platform qualification; and
- Book 2 AC-FUNC-004 end-to-end recovery evidence.
