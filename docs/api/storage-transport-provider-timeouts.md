# Storage and transport provider timeouts

[API reference index](./README.md)

> **Status:** Partial V1 retry/timeout subsystem. Production common-code
> decorators and assembly factories are available. DataLoomBuilder adoption,
> workflow-deadline propagation, protocol connection/request/idle enforcement,
> circuit integration, and complete platform qualification remain open.

## Overview

DataLoom can apply one cooperative provider timeout to every operation of a
`StorageProvider` or `TransportProvider` without exposing platform-specific
network, database, thread, or dispatcher types.

Public runtime types:

- `TimeoutEnforcingStorageProvider`
- `StorageProviderTimeoutRuntime`
- `TimeoutEnforcingTransportProvider`
- `TransportProviderTimeoutRuntime`

The decorators preserve the delegate descriptor and every completed
`ProviderOperationResult` exactly. Caller cancellation and unexpected
programming exceptions propagate unchanged.

## Storage timeout boundary

`TimeoutEnforcingStorageProvider` protects:

- initialization;
- health;
- close;
- outbound change reads;
- inbound change application;
- outbound acknowledgement persistence;
- checkpoint reads; and
- checkpoint writes.

A health, outbound-read, or checkpoint-read timeout is read-only and therefore
uses:

```text
code            = STORAGE_PROVIDER_TIMEOUT
category        = STORAGE
recoverability  = RECOVERABLE
```

Initialization, close, inbound apply, acknowledgement, and checkpoint-write
operations may complete before cancellation is observed. Their timeout uses
`Recoverability.UNKNOWN`. DataLoom does not retry or replay those operations
automatically.

## Transport timeout boundary

`TimeoutEnforcingTransportProvider` protects:

- initialization;
- health;
- close;
- push; and
- pull.

A health timeout is read-only and recoverable. Push and pull timeouts use:

```text
code            = TRANSPORT_PROVIDER_TIMEOUT
category        = NETWORK
recoverability  = UNKNOWN
```

A remote participant may have processed a request even when its response was
lost. The shared runtime therefore does not assume that a timed-out push or pull
is safe to repeat. An adapter-specific idempotency or reconciliation rule must
be explicit before retry.

## Production assembly

```kotlin
val boundedStorage = StorageProviderTimeoutRuntime.create(
    storageProvider = storageProvider,
    clock = runtimeDependencies.clock,
    providerTimeout = SchedulingDelay(10_000),
)

val boundedTransport = TransportProviderTimeoutRuntime.create(
    transportProvider = transportProvider,
    clock = runtimeDependencies.clock,
    providerTimeout = SchedulingDelay(15_000),
)
```

Assembly is structural and side-effect free. It performs no provider operation,
clock read, timeout execution, I/O, or coroutine launch.

## Cooperative cancellation

The common timeout executor uses coroutine cancellation. Providers must reach
cancellation checkpoints for timely interruption. A blocking database driver,
blocking HTTP stack, or native API that ignores coroutine cancellation requires
a platform adapter with a real cancellation or hard-abort mechanism.

A timeout never proves rollback. Storage and remote mutation outcomes remain
unknown unless the provider exposes explicit idempotency or reconciliation
evidence.

## Security and diagnostics

Timeout errors contain only stable error codes, categories, recoverability, and
bounded operation names. They do not contain payloads, credentials,
authorization headers, raw protocol headers, SQL, database paths, exception
messages, provider instances, or arbitrary metadata.

## Current limitations

This slice does not complete DL-040. V1 still requires:

- DataLoomBuilder assembly of bounded storage and transport providers;
- workflow-start/deadline propagation into provider calls;
- protocol-specific connection, request, and idle timeout adapters;
- explicit storage and transport circuit scopes and enriched outcome evidence;
- synchronization-pipeline integration that preserves post-execution evidence;
- KMP iOS production adapters;
- retry/circuit events, metrics, logs, traces, and redacted diagnostics; and
- failure-injection and end-to-end acceptance evidence.
