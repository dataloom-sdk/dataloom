# Provider-protected existing pipeline execution

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Existing synchronization pipelines can run
> through timeout- and circuit-protected storage and transport bridges while
> returning bounded, ordered provider/circuit evidence. DataLoomBuilder adoption,
> durable workflow deadlines, protocol connection/request/idle timeouts, KMP iOS
> persistence, administration, observability, and end-to-end qualification remain.

## Purpose

`ProviderProtectedSynchronizationRuntime` executes an existing
`SynchronizationPipeline` without changing the historical pipeline interface.
It replaces only the execution-local storage and transport providers with
internal bridges backed by:

- `ProtectedStorageOperations`; and
- `ProtectedTransportOperations`.

Scheduler, connectivity, and queue providers are preserved from the original
`SynchronizationExecutionContext`.

## Result model

`ProviderProtectedSynchronizationResult` contains:

- the exact `SynchronizationResult` returned by the pipeline; and
- a defensive, ordered list of `ProviderProtectionOperationEvidence`.

Evidence contains only bounded operational fields:

- provider ID;
- stable operation ID;
- whether the provider ran;
- success, circuit-failure, or semantic-failure classification;
- pre-execution circuit decision;
- canonical error, when present;
- retry time, when present; and
- exact post-execution circuit recording result.

Provider return values, payloads, credentials, headers, checkpoint contents,
exception text, and arbitrary metadata are never stored in the evidence list.

## Fail-closed recording rule

A provider operation may complete successfully while the later circuit-state
write fails, conflicts repeatedly, detects stale probe evidence, or detects
clock regression.

The bridge records both facts, then returns a canonical
`PROVIDER_CIRCUIT_RECORDING_UNCONFIRMED` failure to the pipeline. That error has
`Recoverability.UNKNOWN` so the pipeline cannot continue or authorize automatic
replay merely because circuit recording was not confirmed.

This is especially important for:

- transport push and pull requests whose remote completion may be unknown;
- storage apply, acknowledgement, and checkpoint writes that may already be
  durable; and
- any operation executed under a half-open probe permit.

## Pre-execution decisions

The provider is not invoked when circuit permission returns:

- open circuit;
- another half-open probe in flight;
- clock regression;
- probe generation or lease deadline exhaustion;
- state-store persistence failure; or
- permission compare-and-set contention exhaustion.

The pipeline receives a canonical provider failure and the result preserves the
exact pre-execution evidence.

## Compatibility

The existing `SynchronizationPipeline` and `SynchronizationExecutionContext`
contracts are unchanged. Direct pipeline execution remains available. This
runtime is additive and must be selected explicitly.

The runtime validates before execution that:

- pipeline direction matches the request direction;
- protected storage identity matches the resolved storage provider; and
- protected transport identity matches the resolved transport provider.

Mismatch fails before provider, state-store, clock, timeout, I/O, identifier, or
coroutine activity.

## Cancellation and exceptions

Caller cancellation and unexpected programming exceptions propagate unchanged.
They are not converted into provider failures or partial evidence results.

## Example

```kotlin
val protectedResult = ProviderProtectedSynchronizationRuntime.execute(
    context = executionContext,
    pipeline = pipeline,
    storageOperations = protectedStorageOperations,
    transportOperations = protectedTransportOperations,
)

val synchronizationResult = protectedResult.synchronizationResult
val evidence = protectedResult.operationEvidence
```

## Remaining V1 work

This slice does not complete DL-040. Remaining work includes:

- public DataLoomBuilder/facade assembly;
- strategy and queued-execution adoption;
- durable workflow deadline propagation;
- protocol-specific connection, request, and idle timeout adapters;
- production KMP iOS retry/circuit persistence;
- authorized manual retry, reclassification, open, close, and reset;
- complete retry/circuit events, metrics, logs, traces, health, and diagnostics;
- multi-process, process-death, high-contention, restart, failure-injection, and
  Book 2 `AC-FUNC-004` qualification.
