# Provider circuit protection runtime

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Storage and transport timeout/circuit
> composition can now be assembled into immutable, scope-bound operation
> surfaces. Direct synchronization-pipeline and DataLoomBuilder adoption remain
> separate work.

## Purpose

The lower-level storage and transport circuit adapters require an explicit scope
for every call. That is necessary for correctness, but ordinary pipeline code
must not be responsible for repeatedly selecting scopes, classifiers, state
stores, timeout ordering, or circuit configuration.

The provider protection runtime binds those decisions once and exposes only the
operation methods:

- `StorageCircuitProtectionRuntime.create(...)`
- `TransportCircuitProtectionRuntime.create(...)`
- `ProtectedStorageOperations`
- `ProtectedTransportOperations`
- `StorageCircuitScopes`
- `TransportCircuitScopes`

## Assembly model

Each runtime factory receives exactly one:

- provider instance;
- clock;
- circuit configuration;
- durable circuit state store;
- complete immutable operation-scope set;
- optional provider timeout; and
- failure classifier.

The assembled order is:

```text
Provider
    ↓ optional cooperative provider timeout
TimeoutEnforcingStorageProvider / TimeoutEnforcingTransportProvider
    ↓ shared circuit coordinator and classifier
CircuitBreakerStorageOperationAdapter / CircuitBreakerTransportOperationAdapter
    ↓ validated immutable scope binding
ProtectedStorageOperations / ProtectedTransportOperations
```

All methods return `CircuitBreakerExecutionResult<T>`. Provider execution and
later circuit-state recording remain separate evidence.

## Scope binding

`StorageCircuitScopes` binds initialization, health, close, outbound read,
inbound apply, acknowledgement, checkpoint read, and checkpoint write.

`TransportCircuitScopes` binds initialization, health, close, push, and pull.

Construction validates every provider-bearing and operation-bearing scope. A
mismatch fails before provider execution, state-store access, clock reads, or
timeout execution. Global, workflow, provider, and tenant scopes remain
available when explicitly selected; no fallback scope is inferred.

Once constructed, callers cannot substitute another scope for an individual
operation. This prevents pipeline components from accidentally using a different
operation identity, provider, state store, classifier, timeout order, or circuit
configuration.

## Timeout and replay safety

When configured, provider timeout protection is inside the circuit boundary.
A timeout may therefore contribute to circuit health while preserving its
canonical recoverability:

- storage reads and health may be recoverable;
- storage mutations remain unknown when completion is not confirmed;
- transport health may be recoverable;
- transport initialization, close, push, and pull remain unknown.

Neither runtime automatically repeats a provider operation. An executed provider
success remains visible when later circuit recording fails.

## Side-effect boundary

Factory and operation-surface construction perform no:

- provider operation;
- circuit-state load or compare-and-set;
- clock read;
- timeout execution;
- I/O;
- identifier generation; or
- coroutine launch.

The first state-store and provider access occurs only when an operation method is
explicitly invoked.

## Remaining V1 work

This slice does not complete DL-040. Remaining work includes:

- adopting these surfaces in push, pull, bidirectional, strategy, and facade
  assembly without collapsing execution evidence;
- workflow deadline propagation through durable queue state and restart;
- protocol-specific connection, request, and idle timeout adapters;
- production KMP iOS persistence;
- authorized administration and reclassification;
- complete observability and health integration; and
- contention, restart, failure-injection, and `AC-FUNC-004` qualification.

**Updated 2026-09-28.** `DataLoomBuilder.providerProtectionConfiguration`,
which composes `ProtectedStorageOperations`/`ProtectedTransportOperations`
(this page's own surfaces) with the pipeline registry, now has composed
`AC-FUNC-004` evidence on native Android and KMP iOS -- see
[`builder-provider-protection.md`](./builder-provider-protection.md)'s own
2026-09-28 update. Process-death and half-open-probe cross-process contention
evidence also now exists for the underlying circuit/queue stores (see
[`docs/audits/DL-040-current-acceptance-reconciliation.md`](../audits/DL-040-current-acceptance-reconciliation.md)).
The Status line's "DataLoomBuilder adoption" is resolved by that same
`providerProtectionConfiguration` capability; its separately named "direct
synchronization-pipeline" adoption refers to the different
`ProviderProtectedSynchronizationRuntime` bridge
([`provider-protected-pipeline.md`](./provider-protected-pipeline.md)), which
`DataLoomBuilder` still does not adopt, so that half of the Status line is
unaffected.
