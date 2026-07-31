# DataLoomBuilder circuit-aware queue worker

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. DataLoomBuilder can explicitly assemble one
> circuit-aware queue-worker capability with durable application-supplied
> circuit state. Scheduler-circuit policy, transport/storage assembly, KMP iOS
> persistence, observability, administration, and end-to-end qualification
> remain open.

## Overview

`DataLoomBuilder.circuitQueueWorkerConfiguration(...)` exposes the circuit-aware
queue worker through the public DataLoom facade without collapsing its enriched
recovery, processing, circuit-recording, or scheduling evidence into the legacy
`QueueWorkerRunResult` model.

The direct and circuit-aware queue-worker paths are mutually exclusive. The most
recent queue-worker configuration method is effective:

- `queueWorkerConfiguration(...)` enables `DataLoom.queueWorker` and clears the
  circuit-aware worker configuration;
- `circuitQueueWorkerConfiguration(...)` enables
  `DataLoom.circuitQueueWorker` and clears the direct worker configuration.

This prevents two independently callable workers from competing for the same
queue provider within one DataLoom instance.

## Public contracts

Package: `io.dataloom.runtime.facade`

- `DataLoomCircuitQueueWorker`
- `DataLoomCircuitQueueWorkerSpec`
- `DataLoomBuilder.circuitQueueWorkerConfiguration(...)`
- `DataLoom.circuitQueueWorker`

The existing `DataLoomQueueWorker`, `DataLoomQueueWorkerSpec`,
`queueWorkerConfiguration(...)`, and `DataLoom.queueWorker` remain available.

## Specification

`DataLoomCircuitQueueWorkerSpec` contains:

- the existing `DataLoomQueueWorkerSpec`;
- deterministic `CircuitBreakerConfiguration`;
- an application-supplied durable `CircuitBreakerStateStore`;
- one explicit expired-lease recovery scope;
- explicit acquisition, completion, reschedule, deferral, failure, and
  cancellation scopes; and
- a queue-aware failure classifier, defaulting to
  `QueueCircuitBreakerFailureClassifier`.

The builder does not create an in-memory or process-local circuit store
implicitly. Production durability is an explicit application/platform decision.

## Assembly flow

When circuit-aware queue-worker configuration is effective, `build()`:

1. resolves the queue provider from default provider bindings;
2. resolves the optional scheduler provider;
3. validates every provider-bearing circuit scope against the resolved queue
   provider;
4. validates every operation-bearing scope against the exact queue operation;
5. assembles the normal queued synchronization handler and retry evaluator;
6. applies the existing optional queue-provider timeout to one shared queue
   provider instance;
7. constructs the circuit coordinator and execution gate with the supplied state
   store and runtime clock;
8. constructs one shared queue operation adapter;
9. assembles circuit-aware recovery, acquisition, transitions, and scheduling;
   and
10. exposes the immutable capability through `DataLoom.circuitQueueWorker`.

Invalid queue bindings or circuit scopes fail during `build()` with a sanitized
`DataLoomBuildException` before state-store or provider access.

## Side-effect-free build

Builder configuration and `build()` perform no:

- state-store load or compare-and-set;
- provider initialization, health, or close;
- queue recovery, enqueue, acquisition, or transition;
- retry-policy evaluation;
- timeout execution;
- clock read;
- identifier generation;
- scheduling;
- synchronization execution; or
- coroutine launch.

The circuit state store and queue providers are first accessed when the caller
explicitly invokes the built capability.

## Timeout composition

`DataLoomQueueWorkerSpec.queueProviderTimeout` remains the only queue-provider
timeout setting. When present, the same timeout-protected provider instance is
used for recovery, acquisition, and every transition before circuit result
adaptation.

A canonical `QUEUE_PROVIDER_TIMEOUT` therefore remains durably ambiguous for
replay safety while the queue circuit classifier records dependency
unavailability.

`QueueWorkerConfiguration.schedulerProviderTimeout` remains independent and
applies only to follow-up scheduling. This builder slice does not silently apply
a queue circuit scope to the scheduler.

## Usage

```kotlin
val workerSpec = DataLoomQueueWorkerSpec(
    workResolver = resolver,
    retryPolicy = retryPolicy,
    retryOperation = RetryOperation("sync.queue"),
    configuration = queueWorkerConfiguration,
    queueProviderTimeout = SchedulingDelay(5_000),
)

val circuitSpec = DataLoomCircuitQueueWorkerSpec(
    workerSpec = workerSpec,
    circuitBreakerConfiguration = circuitConfiguration,
    circuitBreakerStateStore = circuitStateStore,
    recoveryScope = CircuitBreakerScope.providerOperation(
        queueProviderId,
        QueueCircuitOperation.RECOVER_EXPIRED_LEASES.retryOperation,
    ),
    processingScopes = processingScopes,
)

val dataLoom = DataLoomBuilder()
    .runtimeDependencies(runtimeDependencies)
    .providers(storageProvider, transportProvider, queueProvider)
    .defaultProviderBindings(bindings)
    .circuitQueueWorkerConfiguration(circuitSpec)
    .build()

val result = requireNotNull(dataLoom.circuitQueueWorker).run(runRequest)
```

## Current limitations

This slice does not complete DL-040. Remaining work includes:

- separately configured scheduler circuit protection with enriched scheduling
  evidence;
- transport and storage timeout/circuit assembly;
- protocol-specific connection, request, and idle timeout adapters;
- production KMP iOS retry/circuit persistence;
- authorized manual retry, reclassification, and circuit administration;
- complete retry/circuit events, metrics, logs, traces, redaction, and support
  diagnostics; and
- process-death, multi-process, high-contention, restart, and complete
  `AC-FUNC-004` qualification.
