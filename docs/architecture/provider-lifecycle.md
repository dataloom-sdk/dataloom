# Provider Lifecycle Coordinator (DL-018)

**Package:** `io.dataloom.core.provider`

## Overview

`ProviderLifecycleCoordinator` orchestrates provider initialization and
shutdown in deterministic order. It receives a `ProviderRegistry` and a
`ProviderInitializationContext` at construction time and coordinates all
registered providers through their lifecycle.

This document covers:

- Lifecycle states
- Initialization order
- Shutdown order
- Initialization rollback
- Shutdown failure isolation
- Coroutine cancellation
- Thread-safety boundary
- KMP constraints
- Security restrictions
- Scope restrictions

---

## Lifecycle states

`ProviderLifecycleCoordinatorState` describes the coordinator's position in
its operational lifecycle. This is distinct from `ProviderLifecycleState`
in `dataloom-api`, which describes an individual provider's lifecycle state.

| State | Description |
|---|---|
| `NOT_INITIALIZED` | Construction complete; `initialize()` not yet called. |
| `INITIALIZING` | `initialize()` in progress. |
| `INITIALIZED` | All providers initialized successfully. |
| `SHUTTING_DOWN` | `shutdown()` in progress. |
| `SHUT_DOWN` | All providers shut down successfully. Terminal state. |
| `FAILED` | Initialization or shutdown failed. Terminal state. |

State transitions are deterministic:

```text
NOT_INITIALIZED
    ↓ initialize() called
INITIALIZING
    ↓ all providers succeed
INITIALIZED
    ↓ shutdown() called
SHUTTING_DOWN
    ↓ all providers succeed
SHUT_DOWN
```

Exceptional transitions:

```text
INITIALIZING → FAILED  (provider initialization failure after rollback)
SHUTTING_DOWN → FAILED  (one or more provider shutdown failures)
```

State never returns to an earlier lifecycle phase. Terminal states
(`SHUT_DOWN` and `FAILED`) are permanent.

Enum ordinals are not a compatibility contract and must not be persisted or
compared by ordinal.

---

## Initialization order

`initialize()` calls `DataLoomProvider.initialize(context)` on each provider
in the order they were registered in the `ProviderRegistry`. This order is
determined solely by registration order — not by `ProviderType` enum ordinal,
provider ID sorting, class name, or hash-map iteration order.

Example registered order:

1. Storage provider
2. Transport provider
3. Scheduler provider

Initialization order:

1. Storage provider
2. Transport provider
3. Scheduler provider

The `ProviderInitializationContext` is passed identically to every provider.

---

## Shutdown order

`shutdown()` calls `DataLoomProvider.close()` on each successfully initialized
provider in reverse initialization order.

Using the same example, shutdown order is:

1. Scheduler provider
2. Transport provider
3. Storage provider

Only providers that were successfully initialized are shut down. A provider
that failed initialization is never shut down. A successfully initialized
provider is shut down exactly once.

---

## Initialization rollback

When a provider returns `ProviderOperationResult.Failure` during initialization:

1. Initialization stops — providers registered after the failing provider are
   not initialized.
2. The primary initialization failure is recorded in `ProviderLifecycleResult.InitializeFailure.primaryFailure`.
3. Previously initialized providers are shut down in reverse initialization
   order (rollback).
4. Any rollback failures are recorded separately in
   `ProviderLifecycleResult.InitializeFailure.rollbackFailures`.
5. The coordinator transitions to `ProviderLifecycleCoordinatorState.FAILED`.

Primary initialization failure is not replaced by a rollback failure.

Example:

- A initializes → success
- B initializes → success
- C initializes → **failure**

Rollback order:

1. B shutdown
2. A shutdown

D and later providers are not initialized.

---

## Shutdown failure isolation

Shutdown continues past individual provider failures. When a provider returns
`ProviderOperationResult.Failure` from `close()`:

- The failure is recorded in `ProviderLifecycleResult.ShutdownFailure.failures`.
- The next provider in the reverse-order sequence is still shut down.
- All failures are collected and returned when shutdown completes.
- The coordinator transitions to `ProviderLifecycleCoordinatorState.FAILED`.

`failures` is ordered by shutdown invocation order (reverse initialization order).

---

## Lifecycle results

`ProviderLifecycleResult` is a sealed interface with the following variants:

| Variant | Description |
|---|---|
| `InitializeSuccess` | All providers initialized successfully. |
| `ShutdownSuccess` | All providers shut down successfully. |
| `InitializeFailure` | A provider initialization failed; rollback complete. |
| `ShutdownFailure` | One or more providers failed to shut down. |
| `InvalidOperation` | Lifecycle method called in an invalid state. |

Invalid operations are returned, not thrown. When `initialize()` is called
on an already-initialized coordinator, `InvalidOperation` is returned
indicating the current state and the rejected operation.

---

## Lifecycle failures

`ProviderLifecycleFailure` preserves:

- `providerId: ProviderId` — the failing provider.
- `operation: ProviderLifecycleOperation` — `INITIALIZE` or `SHUTDOWN`.
- `error: DataLoomError` — the canonical failure.

`ProviderLifecycleFailure` is immutable and provides value-based equality.
It does not expose raw `Throwable` or stack traces.

---

## Coroutine cancellation

`CancellationException` thrown by provider operations propagates normally.
The coordinator does not catch, convert, or suppress `CancellationException`.

`CancellationException` is never converted into:

- `DataLoomError`
- `ProviderOperationResult.Failure`
- `ProviderLifecycleFailure`
- a lifecycle result variant

**Cleanup after cancellation is not guaranteed.** If `initialize()` or
`shutdown()` is cancelled externally, rollback or partial shutdown may not
occur. Do not rely on cleanup behavior after external coroutine cancellation.

---

## Thread-safety boundary

`ProviderLifecycleCoordinator` does not provide concurrency control. It does
not choose a dispatcher and does not expose a `CoroutineScope`.

**Callers must serialize `initialize()` and `shutdown()` calls.** Concurrent
invocation without external coordination produces undefined behavior.

Provider implementations must follow the thread-safety expectations documented
on `DataLoomProvider`. The coordinator calls `initialize()` and `close()` from
whatever execution context the caller provides.

Do not introduce JVM locks, Android synchronization types, or third-party
atomic libraries in `ProviderLifecycleCoordinator` without an approved ADR.

---

## Explicit dependency injection

All `ProviderLifecycleCoordinator` dependencies are supplied at construction
time. There are no default values, no global registries, and no service
locators.

```kotlin
val coordinator = ProviderLifecycleCoordinator(
    registry = registry,
    context = ProviderInitializationContext(
        runtimeVersion = RuntimeVersion("1.0.0"),
    ),
)
```

`ProviderLifecycleCoordinator` does not depend on Hilt, Dagger, Koin, or any
other dependency-injection framework.

---

## KMP compatibility

`ProviderLifecycleCoordinator` uses Kotlin standard-library and DataLoom API
types only. It does not require:

- Android APIs
- JVM-only types (`java.util.*`, `java.lang.*` not used)
- Platform-specific synchronization
- Third-party coroutines test libraries

---

## Security restrictions

Lifecycle diagnostics and results must not expose:

- Credentials
- Authorization headers
- Checkpoint tokens
- Payload bytes
- Encryption keys
- Personal data
- Stack traces

`DataLoomError.message` must contain only sanitized diagnostic information.
`ProviderLifecycleFailure` does not expose raw `Throwable`.

---

## Scope restrictions

`ProviderLifecycleCoordinator` performs provider lifecycle operations only.
It does not implement:

- Synchronization orchestration
- Synchronization runtime
- Queue processing
- Retry execution
- Conflict resolution
- Event dispatch
- Scheduling
- Connectivity observation
- Provider auto-discovery
- Provider dependency graph resolution
- Default-provider selection
- Provider replacement
- Runtime provider mutation

Synchronization runtime is a later DataLoom issue.

---

## Example

```kotlin
val registry = ProviderRegistry(
    listOf(storageProvider, transportProvider, schedulerProvider)
)

val context = ProviderInitializationContext(
    runtimeVersion = RuntimeVersion("1.0.0"),
)

val coordinator = ProviderLifecycleCoordinator(registry, context)

// Initialize all providers
when (val result = coordinator.initialize()) {
    is ProviderLifecycleResult.InitializeSuccess -> {
        // All providers ready
    }
    is ProviderLifecycleResult.InitializeFailure -> {
        // result.primaryFailure contains the failing provider and error
        // result.rollbackFailures contains any rollback failures
    }
    is ProviderLifecycleResult.InvalidOperation -> {
        // Called in invalid state: result.state, result.operation
    }
    else -> Unit
}

// Later: shut down all providers
when (val result = coordinator.shutdown()) {
    is ProviderLifecycleResult.ShutdownSuccess -> {
        // All providers shut down cleanly
    }
    is ProviderLifecycleResult.ShutdownFailure -> {
        // result.failures contains all failures in shutdown order
    }
    is ProviderLifecycleResult.InvalidOperation -> {
        // Called in invalid state
    }
    else -> Unit
}
```

---

## Synchronization orchestration not implemented

`ProviderLifecycleCoordinator` does not implement or trigger synchronization
orchestration. It initializes and shuts down providers. No queue processing,
retry execution, conflict resolution, event dispatch, or scheduling behavior
exists in `ProviderLifecycleCoordinator`.
