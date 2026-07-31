# DataLoomBuilder provider protection

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. `DataLoomBuilder` can expose an explicit
> protected direct-synchronization capability using application-supplied durable
> circuit stores, exact operation scopes, and optional storage/transport provider
> timeouts. Strategy and queued-execution adoption, durable workflow deadlines,
> protocol timeouts, KMP iOS persistence, administration, observability, and
> end-to-end qualification remain open.

## Overview

`DataLoomBuilder.providerProtectionConfiguration(...)` assembles an optional
`DataLoomProtectedSynchronization` capability available through
`DataLoom.protectedSynchronization`.

The historical `DataLoom.synchronize(...)` methods are unchanged. Protection is
never silently enabled for existing applications; callers select the new
capability explicitly.

## Public specification

`DataLoomProviderProtectionSpec` contains independent:

- `DataLoomStorageProtectionSpec`; and
- `DataLoomTransportProtectionSpec`.

Each side requires:

- deterministic `CircuitBreakerConfiguration`;
- an application/platform-supplied durable `CircuitBreakerStateStore`;
- exact operation-specific scopes;
- an optional provider timeout; and
- an optional failure classifier with a safe storage/transport default.

The builder creates no in-memory state-store fallback and does not infer broad,
tenant, workflow, provider, or operation scopes.

## Build-time validation

Protected synchronization currently requires `defaultProviderBindings` because
its public facade executes against those exact providers.

During `build()`, DataLoom:

1. resolves the already validated default storage and transport providers;
2. applies each optional provider timeout before circuit adaptation;
3. validates every provider-bearing scope against the resolved provider;
4. validates every operation-bearing scope against the exact provider method;
5. creates one immutable protected storage surface;
6. creates one immutable protected transport surface;
7. creates a protected synchronization admission coordinator using the same
   lifecycle, resolver, pipeline registry, connectivity preflight, runtime
   dependencies, and lifecycle emitter as direct synchronization; and
8. exposes the immutable facade capability.

Invalid scopes produce a sanitized `DataLoomBuildException` before provider,
state-store, clock, timeout, I/O, identifier, event, or coroutine activity.

## Execution semantics

`DataLoomProtectedSynchronization.synchronize(request)` follows the existing
admission order:

1. provider lifecycle must be initialized;
2. default provider bindings are resolved;
3. the direction pipeline is selected;
4. connectivity preflight is evaluated;
5. Started is emitted;
6. the existing pipeline executes through protected provider bridges;
7. Completed is emitted with the exact pipeline result; and
8. `ProviderProtectedSynchronizationExecutionResult.Executed` returns the exact
   pipeline result plus ordered bounded provider/circuit evidence.

Admission failures are returned through
`ProviderProtectedSynchronizationExecutionResult.Rejected`, which preserves the
existing `SynchronizationExecutionResult.Rejected` model and its invariants.

## Evidence safety

The protected result preserves whether each provider operation:

- was rejected before invocation;
- succeeded;
- returned a circuit-eligible failure;
- returned a semantic non-circuit failure; and
- completed before a later circuit recording failure.

Provider values, payloads, credentials, headers, checkpoint contents, exception
text, and arbitrary metadata are not stored in the evidence list.

Provider success followed by unconfirmed circuit recording is returned to the
pipeline as `PROVIDER_CIRCUIT_RECORDING_UNCONFIRMED` with
`Recoverability.UNKNOWN`. The result still proves that the provider executed,
preventing unsafe automatic replay.

## Compatibility

- No configuration: `DataLoom.protectedSynchronization == null`.
- Configured: only `protectedSynchronization` uses the protection policy.
- Direct `DataLoom.synchronize(...)` remains source- and behavior-compatible.
- Custom pre-V1 `DataLoom` implementations retain compatibility through a
  default-null property getter.

## Example

```kotlin
val protection = DataLoomProviderProtectionSpec(
    storage = DataLoomStorageProtectionSpec(
        circuitBreakerConfiguration = storageCircuitConfiguration,
        circuitBreakerStateStore = storageCircuitStore,
        scopes = storageScopes,
        providerTimeout = SchedulingDelay(5_000),
    ),
    transport = DataLoomTransportProtectionSpec(
        circuitBreakerConfiguration = transportCircuitConfiguration,
        circuitBreakerStateStore = transportCircuitStore,
        scopes = transportScopes,
        providerTimeout = SchedulingDelay(10_000),
    ),
)

val dataLoom = DataLoomBuilder()
    .runtimeDependencies(runtimeDependencies)
    .providers(storageProvider, transportProvider)
    .defaultProviderBindings(bindings)
    .providerProtectionConfiguration(protection)
    .build()

dataLoom.initialize()
val protectedResult = requireNotNull(dataLoom.protectedSynchronization)
    .synchronize(request)
```

## Remaining V1 work

This slice does not complete DL-040. Remaining work includes:

- strategy and queued-execution adoption;
- durable workflow-start/deadline propagation through queueing and restart;
- protocol-specific connection, request, and idle timeout adapters;
- production KMP iOS retry/circuit persistence;
- authorized manual retry, reclassification, open, close, and reset;
- complete retry/circuit events, metrics, logs, traces, health, and support
  diagnostics; and
- multi-process, process-death, high-contention, restart, failure-injection, and
  Book 2 `AC-FUNC-004` qualification.
