# Storage provider timeout and circuit boundary

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Storage lifecycle, reads, mutations,
> acknowledgements, and checkpoints now have cooperative provider-timeout and
> explicit durable circuit adapters. Direct pipeline/builder assembly remains
> open.

## Stable operations

`StorageCircuitOperation` defines:

```text
storage.initialize
storage.health
storage.close
storage.read-outbound-changes
storage.apply-inbound-changes
storage.acknowledge-outbound-changes
storage.read-checkpoint
storage.write-checkpoint
```

Provider-bearing scopes must identify the protected storage provider.
Operation-bearing scopes must identify the exact operation. Global and workflow
scopes remain explicit choices. No scope is inferred.

## Provider timeout

`TimeoutEnforcingStorageProvider` applies the shared cooperative provider
boundary to every current storage operation. `StorageProviderTimeoutRuntime`
assembles it from a `StorageProvider`, `DataLoomClock`, and `SchedulingDelay`.

The canonical timeout code is:

```text
STORAGE_PROVIDER_TIMEOUT
```

Read and health timeouts are recoverable. Apply, acknowledgement, checkpoint
write, initialize, and close timeouts have `Recoverability.UNKNOWN`, because the
durable mutation or lifecycle transition may already have occurred.

The timeout code still contributes to storage circuit availability. Unknown
completion does not authorize replay; reconciliation, state lookup, or an
independently proven idempotency contract is required.

## Circuit adapter

`CircuitBreakerStorageOperationAdapter` acquires permission, invokes the exact
storage operation at most once, classifies its canonical result, records the
outcome, and returns the complete `CircuitBreakerExecutionResult`.

It deliberately does not implement `StorageProvider`. A plain provider result
cannot preserve both an already-executed durable mutation and a later circuit
persistence failure without losing replay-critical evidence.

`CircuitBreakerExecutionResult.Executed` proves that the storage operation ran
once. Its provider result and record result must be evaluated separately. A
successful apply, acknowledgement, or checkpoint write followed by failed
circuit recording must not be converted into a provider failure or replayed
automatically.

## Timeout classification

`StorageCircuitBreakerFailureClassifier` treats the stable provider timeout as
an availability failure even when a mutation timeout retains unknown completion
for replay safety. Other errors follow the default classifier.

## Construction boundary

Timeout runtime and circuit adapter construction perform no provider call,
state-store access, clock read, timeout execution, identifier generation,
coroutine launch, or storage operation.

## Remaining work

- integrate exact storage evidence into push, pull, bidirectional, strategy, and
  conflict pipelines;
- expose explicit builder configuration;
- add durable KMP iOS circuit persistence;
- add events, metrics, logs, traces, redaction, and administration; and
- complete restart, contention, fault-injection, and `AC-FUNC-004`
  qualification.
