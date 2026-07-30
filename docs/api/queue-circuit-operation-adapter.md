# Queue Circuit Operation Adapter

> **Status:** Partial V1 runtime slice. Explicit queue-operation circuit
> permission and outcome recording exist without collapsing post-execution
> evidence. Automatic queue-worker/submission circuit assembly, operations,
> observability, and end-to-end qualification remain open.

## Purpose

`CircuitBreakerQueueOperationAdapter` protects explicit `QueueProvider`
operations behind `CircuitBreakerExecutionGate` while returning the complete
`CircuitBreakerExecutionResult`.

The adapter covers:

- initialize;
- health;
- close;
- enqueue;
- acquire;
- complete;
- reschedule;
- defer;
- fail;
- cancel; and
- recover expired leases.

## Why it does not implement QueueProvider

A queue mutation may run successfully, fail canonically, or commit durably before
a later circuit-state update fails. `CircuitBreakerExecutionResult.Executed`
therefore preserves two independent outcomes:

1. `operationResult` — the exact provider success, eligible failure, or semantic
   non-circuit failure; and
2. `recordResult` — the exact result of updating durable circuit state.

Mapping that result back to a plain `ProviderOperationResult` would necessarily
hide one of those facts. In particular, returning only a circuit persistence
failure could cause a caller to replay a queue mutation that already ran.

For this reason, the adapter is additive and does not pretend to be a transparent
`QueueProvider` decorator.

## Stable operation identities

`QueueCircuitOperation` publishes explicit `RetryOperation` identities:

| Queue operation | Circuit operation identity |
|---|---|
| initialize | `queue.initialize` |
| health | `queue.health` |
| close | `queue.close` |
| enqueue | `queue.enqueue` |
| acquire | `queue.acquire` |
| complete | `queue.complete` |
| reschedule | `queue.reschedule` |
| defer | `queue.defer` |
| fail | `queue.fail` |
| cancel | `queue.cancel` |
| recover expired leases | `queue.recover-expired-leases` |

Enum ordinals are not persistence or compatibility identifiers. Persist or
configure the `RetryOperation.value` string.

## Explicit scope validation

Every method receives an exact `CircuitBreakerScope`.

- Provider-bearing scopes must identify the protected queue provider.
- Provider-operation and tenant-provider-operation scopes must use the exact
  operation identity for the invoked method.
- Global and workflow scopes are accepted without inventing a provider or
  operation.
- No scope inheritance, fallback, or automatic tenant/workflow derivation is
  performed.

A scope mismatch fails before circuit-state access and before provider
invocation.

## Queue timeout classification

`QueueCircuitBreakerFailureClassifier` delegates ordinary classification to
`DefaultCircuitBreakerFailureClassifier`, with one queue-specific rule:

```text
ErrorCategory.QUEUE
+ code QUEUE_PROVIDER_TIMEOUT
→ RECORD_FAILURE
```

`QUEUE_PROVIDER_TIMEOUT` uses `Recoverability.UNKNOWN` because the durable
mutation outcome may be ambiguous. That prevents unsafe automatic replay, but a
provider timeout still represents dependency unavailability and therefore
contributes to circuit health.

Other unknown queue failures retain the default non-circuit classification.
Applications may inject a classifier for additional provider-specific timeout
codes.

## Execution outcomes

### Permission denied before execution

The queue provider is not called. The result is one of:

- `Rejected` with a stable rejection reason and optional retry instant;
- `PermissionPersistenceFailure`; or
- `PermissionContentionLimitReached`.

### Operation executed

The provider is invoked at most once. The result is always `Executed`, containing:

- `Success(value)` for exact provider success;
- `Failure(error)` for a circuit-eligible canonical failure; or
- `NonCircuitFailure(error)` for a semantic failure proving the dependency
  responded;

plus the exact `CircuitBreakerRecordResult`.

A post-execution `PersistenceFailure`, `ContentionLimitReached`, stale probe,
expired probe lease, or clock regression never hides the provider outcome.

## Cancellation and exceptions

Caller cancellation and unexpected provider exceptions propagate unchanged.
They are not translated into provider failures and are not recorded as ordinary
circuit outcomes by this adapter.

## Construction boundary

Constructing the adapter performs no:

- provider lifecycle or queue operation;
- circuit-state load or update;
- clock read;
- queue mutation;
- coroutine launch; or
- identifier generation.

## KMP boundary

The public surface uses DataLoom contracts and Kotlin Multiplatform common types
only. It exposes no Room, SQLite, SQLDelight, Android, JVM-only, Apple storage,
dispatcher, or coroutine-scope type.

## Remaining V1 work

- circuit-aware queue submission that preserves encoding/preflight and enriched
  execution evidence;
- circuit-aware queue worker processing without losing post-execution outcomes;
- automatic `DataLoomBuilder` assembly for explicit queue circuit policies;
- durable KMP iOS circuit-state storage;
- queue circuit events, bounded metrics, structured logs, traces, redaction, and
  correlation;
- authorized and audited manual circuit open, close, and reset;
- multi-process, transaction-race, process-death, restart, and half-open probe
  qualification; and
- complete Book 2 `AC-FUNC-004` evidence.
