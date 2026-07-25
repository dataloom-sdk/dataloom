# Runtime Assembly (DL-033)

## Overview

DL-033 introduces the public `DataLoom` facade and `DataLoomBuilder` that
assemble the complete synchronization runtime from the components implemented
in DL-018 through DL-032.

The builder validates all mandatory configuration and constructs an immutable
runtime graph. No provider operation, clock read, I/O, or coroutine is
initiated during `build()`.

---

## Module

`dataloom-runtime/src/commonMain`

Package: `io.dataloom.runtime.facade`

---

## Component assembly

```
DataLoomBuilder.build()
│
├── ProviderRegistry
│     └── registers all supplied DataLoomProvider instances
│
├── ProviderLifecycleCoordinator
│     └── owns lifecycle for all registered providers
│
├── SynchronizationProviderResolver
│     └── resolves providers from the registry for each request
│
├── SynchronizationPipelineRegistry
│     ├── OutboundPushSynchronizationPipeline  (default or custom)
│     ├── InboundPullSynchronizationPipeline   (default or custom)
│     └── BidirectionalSynchronizationPipeline (composed from outbound + inbound)
│
├── [Optional] SynchronizationObserverRegistry
│     └── registered in observer-registration order
│
├── [Optional] SynchronizationEventDispatcher
│     └── driven by SynchronizationObserverRegistry
│
├── [Optional] DispatchingSynchronizationLifecycleEventEmitter
│     └── uses SynchronizationEventDispatcher + RuntimeDependencies
│
├── [Optional] SynchronizationConnectivityPreflight
│     └── assembled when connectivityConfiguration is supplied
│
├── SynchronizationExecutionCoordinator
│     ├── providerResolver
│     ├── pipelineRegistry
│     ├── runtimeDependencies
│     ├── optional lifecycleEventEmitter
│     └── optional connectivityConfiguration + connectivityPreflight
│
├── [Optional] QueueWorkerCoordinator
│     ├── RetryEvaluator
│     ├── QueuedSynchronizationExecutionHandler
│     ├── DurableQueueExecutionProcessor
│     └── SchedulerProvider (optional per DL-032)
│
└── DefaultDataLoom
      ├── providerLifecycleCoordinator
      ├── executionCoordinator
      ├── defaultProviderBindings
      └── optional DefaultDataLoomQueueWorker
```

---

## Build-time invariants

`DataLoomBuilder.build()` enforces:

| Invariant | Failure |
|---|---|
| `RuntimeDependencies` is set | `DataLoomBuildException` |
| At least one provider is registered | `DataLoomBuildException` |
| Default `SynchronizationProviderBindings` is set | `DataLoomBuildException` |
| Storage provider ID exists and matches type and interface | `DataLoomBuildException` |
| Transport provider ID exists and matches type and interface | `DataLoomBuildException` |
| No duplicate provider IDs | `IllegalArgumentException` from `ProviderRegistry` |
| No duplicate observer IDs | `IllegalArgumentException` from `SynchronizationObserverRegistry` |
| No duplicate pipeline directions | `IllegalArgumentException` from `SynchronizationPipelineRegistry` |
| Queue worker requires valid queue provider binding | `DataLoomBuildException` |

No provider method, clock read, I/O, or coroutine runs during `build()`.

---

## Pipeline assembly

Default pipelines are assembled only when no custom pipeline is registered for
that direction:

1. The builder checks whether a custom outbound pipeline was registered.
   If not, it constructs `OutboundPushSynchronizationPipeline` from
   `OutboundPushPipelineConfiguration`.

2. The builder checks whether a custom inbound pipeline was registered.
   If not, it constructs `InboundPullSynchronizationPipeline` from
   `InboundPullPipelineConfiguration`.

3. The builder checks whether a custom bidirectional pipeline was registered.
   If not, it constructs `BidirectionalSynchronizationPipeline` from the
   final outbound and inbound pipelines (custom or default).

All three pipelines are registered in `SynchronizationPipelineRegistry` before
`DefaultDataLoom` is constructed.

---

## Event infrastructure assembly

When no observers are supplied:

- `SynchronizationObserverRegistry` is not created.
- `SynchronizationEventDispatcher` is not created.
- No event emitter is passed to `SynchronizationExecutionCoordinator`.
- No event objects are created during synchronization.

When observers are supplied:

- `SynchronizationObserverRegistry` is constructed with the supplied observer
  list in registration order.
- `SynchronizationEventDispatcher` is constructed from the observer registry.
- `DispatchingSynchronizationLifecycleEventEmitter` is constructed using the
  dispatcher and `RuntimeDependencies` (clock, event ID generator).
- The emitter is passed to `SynchronizationExecutionCoordinator`.

---

## Connectivity assembly

When no `SynchronizationConnectivityConfiguration` is supplied:

- `SynchronizationConnectivityPreflight` is not created.
- `SynchronizationExecutionCoordinator` receives no connectivity configuration.
- Execution proceeds unconditionally.

When connectivity configuration is supplied:

- `SynchronizationConnectivityPreflight` is constructed from the configuration.
- Both the configuration and the preflight are passed to
  `SynchronizationExecutionCoordinator`.
- At execution time, the coordinator selects the connectivity provider by the
  `connectivityProviderId` in the active bindings.
- Direct-rejection and queued-deferral behavior follow DL-031 semantics.

---

## Queue-worker assembly

When `DataLoomQueueWorkerSpec` is supplied:

1. The queue provider is retrieved from `ProviderRegistry` by the queue
   provider ID in the default bindings.
2. A `RetryEvaluator` is constructed from `RetryPolicy`.
3. A `QueuedSynchronizationExecutionHandler` is constructed from the work
   resolver, retry evaluator, and execution coordinator.
4. A `DurableQueueExecutionProcessor` is constructed from the execution handler,
   queue provider, and optional retry evaluator.
5. A `QueueWorkerCoordinator` is constructed from the processor, configuration,
   and optional scheduler provider.
6. `DefaultDataLoomQueueWorker` wraps the coordinator and is returned through
   `DataLoom.queueWorker`.

The scheduler provider is optional. When absent, `QueueWorkerCoordinator`
follows DL-032 scheduler-absent behavior.

No queue operation is performed during `build()`.

---

## Facade delegation

`DefaultDataLoom` is an internal implementation of the `DataLoom` interface:

| Facade method | Delegates to |
|---|---|
| `providerLifecycleState` | `ProviderLifecycleCoordinator.state` |
| `queueWorker` | the optional `DefaultDataLoomQueueWorker` |
| `initialize()` | `ProviderLifecycleCoordinator.initialize()` |
| `shutdown()` | `ProviderLifecycleCoordinator.shutdown()` |
| `synchronize(request)` | `SynchronizationExecutionCoordinator.execute(request, defaultBindings)` |
| `synchronize(request, bindings)` | `SynchronizationExecutionCoordinator.execute(request, bindings)` |

The facade adds no logic, retry, or transformation to any result. Results are
returned exactly as produced by the underlying coordinator.

---

## Immutability after build

After `build()` completes:

- `ProviderRegistry` is immutable
- `SynchronizationObserverRegistry` is immutable
- `SynchronizationPipelineRegistry` is immutable
- Default `SynchronizationProviderBindings` are immutable
- Internal coordinators are not replaceable
- Internal components are not exposed through `DataLoom`
- No service-locator API exists

---

## Concurrency

- `initialize()` and `shutdown()` should be serialized by the caller.
- `ProviderLifecycleCoordinator` thread-safety follows DL-018 invariants.
- Concurrent `synchronize()` calls follow `SynchronizationExecutionCoordinator`
  limitations.
- Queue-worker concurrency follows `QueueProvider` and `SchedulerProvider`
  guarantees.
- `DataLoom` introduces no global lock, single-flight, or background scope.

---

## Cancellation

`CancellationException` from:

- `initialize()`
- `shutdown()`
- `synchronize()`
- `queueWorker.run()`

propagates to the caller unchanged. The facade does not convert cancellation
into a lifecycle failure, synchronization result, queue-worker result, or
`DataLoomError`.

---

## Security

`DataLoomBuildException` diagnostic messages are restricted to:

- missing configuration field names
- `ProviderId` values
- `ProviderType` values
- `SynchronizationDirection` values
- structural binding failure reasons

No provider implementation state, payloads, credentials, tokens, checkpoint
values, or stack traces are included in any public diagnostic.

---

## KMP compatibility

All production code in `io.dataloom.runtime.facade` is declared in
`commonMain`. No Android API, JVM-only lock, reflection, ServiceLoader, or
external dependency is used.
