# Storage and transport circuit adapters

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Storage and transport provider operations can
> now be protected by explicit durable circuit scopes while preserving exact
> provider execution and later circuit-recording evidence. Direct pipeline and
> DataLoomBuilder adoption remain separate work.

## Purpose

A storage mutation or remote request can complete before a timeout, response
loss, or later circuit-state persistence failure becomes visible to the caller.
A transparent provider decorator cannot represent both facts safely:

1. whether the provider operation executed and what it returned; and
2. whether the subsequent circuit-state update was accepted.

`CircuitBreakerStorageOperationAdapter` and
`CircuitBreakerTransportOperationAdapter` therefore return the complete
`CircuitBreakerExecutionResult<T>` rather than collapsing the result back into
`ProviderOperationResult<T>`.

## Stable operations

Storage operations:

- `storage.initialize`
- `storage.health`
- `storage.close`
- `storage.read-outbound-changes`
- `storage.apply-inbound-changes`
- `storage.acknowledge-outbound-changes`
- `storage.read-checkpoint`
- `storage.write-checkpoint`

Transport operations:

- `transport.initialize`
- `transport.health`
- `transport.close`
- `transport.push-changes`
- `transport.pull-changes`

Each operation is represented by a public enum entry carrying a stable
`RetryOperation` value for provider-operation circuit scopes.

## Scope validation

Every invocation receives one explicit `CircuitBreakerScope`.

- Provider-bearing scopes must match the protected provider descriptor.
- Operation-bearing scopes must match the exact method being invoked.
- No provider, operation, tenant, workflow, or global fallback is inferred.
- Mismatch fails before circuit-state loading or provider invocation.

## Timeout composition

Provider timeout protection is applied before circuit classification:

```text
StorageProvider / TransportProvider
    ↓ cooperative provider timeout
TimeoutEnforcingStorageProvider / TimeoutEnforcingTransportProvider
    ↓ permission, invocation, classification, recording
CircuitBreakerStorageOperationAdapter / CircuitBreakerTransportOperationAdapter
    ↓
CircuitBreakerExecutionResult<T>
```

`STORAGE_PROVIDER_TIMEOUT` and `TRANSPORT_PROVIDER_TIMEOUT` contribute to
circuit availability even when their canonical recoverability is `UNKNOWN`.
This separates two decisions:

- circuit health may record dependency unavailability; and
- replay remains prohibited when completion is not confirmed.

Storage health, outbound reads, and checkpoint reads remain recoverable.
Storage initialization, close, apply, acknowledgement, and checkpoint writes
remain unknown on timeout.

Transport health remains recoverable. Transport initialization, close, push,
and pull remain unknown on timeout. Pull is not assumed idempotent by the shared
provider contract.

## Evidence preservation

When permission is granted, a provider method is invoked at most once. An
`Executed` result preserves both the exact provider outcome and the later
`CircuitBreakerRecordResult`.

A successful storage write or remote request followed by a failed circuit-state
write remains visible as provider success plus unconfirmed recording. Callers
must not automatically repeat the provider operation merely because circuit
recording failed.

Pre-execution rejection, state-store load failure, and contention exhaustion are
reported without invoking the provider.

## Cancellation and side effects

Caller cancellation and unexpected programming exceptions propagate unchanged.
Adapter and timeout construction perform no provider call, state-store access,
clock read, timeout execution, identifier generation, I/O, or coroutine launch.

## Remaining V1 work

This slice does not complete DL-040. Remaining work includes:

- direct push, pull, bidirectional, strategy, and builder assembly;
- workflow deadline propagation through queued and restarted work;
- protocol-specific connection, request, and idle timeout adapters;
- production KMP iOS retry and circuit persistence;
- authorized manual retry, reclassification, and circuit administration;
- complete events, metrics, logs, traces, diagnostics, and health integration;
- multi-process, high-contention, restart, failure-injection, and
  `AC-FUNC-004` qualification.
