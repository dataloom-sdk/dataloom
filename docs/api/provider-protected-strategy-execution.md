# Provider-protected strategy execution

## Status

**Partial V1 subsystem.** DataLoom can now execute the currently supported
built-in network-only and remote-first strategy paths through explicit provider
timeout and circuit boundaries while preserving the existing immutable strategy
evaluation and plan. Complete six-strategy behavior and full V1 qualification
remain open.

## Public assembly

Applications configure the additive capability with:

```kotlin
DataLoomBuilder()
    .strategyProviderProtectionConfiguration(
        DataLoomStrategyProviderProtectionSpec(
            storage = storageProtection,
            transport = transportProtection,
            localFallback = fallbackProtection,
        ),
    )
```

The resulting facade is available from:

```kotlin
val protected = dataLoom.protectedStrategySynchronization
```

The historical `DataLoom.synchronize(StrategySynchronizationRequest)` methods
remain unchanged and do not silently acquire circuit policy.

## Plan-aware provider selection

Protection is applied only after the existing evaluator has produced the
immutable effective plan and after capability-aware provider resolution.

- Network-only resolves and protects transport without resolving or touching
  storage or queue.
- Remote-first protects the exact resolved transport and storage roles.
- Application-owned local fallback uses its own state store, scope, timeout, and
  classifier. Storage protection is not silently reused for fallback policy.
- Scheduler, connectivity, and queue roles remain the exact resolved provider
  instances until separately reviewed protection boundaries are configured.

A resolved required provider without a matching protection specification is
rejected before provider invocation. No broad global scope, default state store,
or in-memory circuit is inferred.

## Evidence model

`ProviderProtectedStrategySynchronizationResult` contains:

- the exact existing `StrategySynchronizationExecutionResult`;
- an ordered defensive snapshot of
  `ProviderProtectionOperationEvidence`.

Evidence records permission, invocation category, canonical failure,
post-execution circuit-recording result, rejection reason, and bounded retry
instant. It does not include provider return values, local domain payloads,
credentials, headers, checkpoint contents, exception text, or arbitrary
metadata.

## Remote-first local fallback

When the evaluated remote-first plan permits a local fallback, the raw storage
provider must implement `StrategyLocalFallbackProvider`, and the protected
strategy configuration must provide
`DataLoomStrategyLocalFallbackProtectionSpec`.

The stable operation identity is:

```text
strategy.evaluate-local-fallback
```

Fallback evaluation is invoked at most once after its own circuit permission.
An optional cooperative provider timeout is applied before fallback circuit
classification. A fallback timeout is a local dependency availability failure;
semantic `Unavailable` remains a normal provider response.

## Replay and failure safety

- Provider execution followed by unconfirmed circuit recording remains visible
  in operation evidence.
- Such an outcome is not converted into success and is not automatically
  replayed.
- Missing protection or scope mismatch is a typed strategy rejection before
  provider invocation.
- Caller cancellation and unexpected programming exceptions propagate.
- A fresh evidence collector is created per call, so concurrent calls do not
  share mutable operation evidence.

## Current limits

This slice does not complete:

- offline-first, cache-first, hybrid, or adaptive runtime behavior;
- protected strategy execution from durable strategy queue records;
- protocol-specific connection, request, and idle timeout adapters;
- production KMP iOS circuit/retry/deadline persistence;
- authorized strategy retry/reclassification or circuit administration;
- durable strategy events, metrics, logs, traces, or operational read models;
- multi-process, process-death, high-contention, and Book 2 `AC-FUNC-004`
  qualification.

## Persisted accepted-plan execution

`DataLoomProtectedStrategySynchronization.synchronizeAcceptedPlan(...)` executes
the exact persisted plan and continuation without evaluating a profile or
current runtime evidence. Storage, transport, local fallback, and reconciliation
are independently protected by the configured circuit and provider-timeout
specifications. Missing protection for a resolved required role rejects before
provider invocation.

Plan-bearing protected queue work uses this method directly and returns ordered
provider/circuit evidence in `ProviderProtectedQueueEntryExecutionResult`.
Legacy protected synchronization remains unchanged for entries without an
accepted plan.
