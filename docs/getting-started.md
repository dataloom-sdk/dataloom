# Getting started with the current DataLoom foundation

> **Audience:** Developers evaluating DataLoom from this source checkout or adding it to an existing Android app
> **Purpose:** Get one direct `DataLoom.synchronize(request)` call running against the current public facade, then map that shape onto real app providers
> **Status:** **Current** facade walkthrough for the checked-in pre-V1 foundation. **Partial** product coverage only; this page is not a production-readiness claim and does not prove the full six-strategy V1 engine.

[Project overview](../README.md) ·
[Documentation hub](./README.md) ·
[DataLoom facade](./api/dataloom-facade.md)

This page expands the quick-start pattern already shown in
[DataLoom facade](./api/dataloom-facade.md#quick-start):

```kotlin
val dataLoom = DataLoomBuilder()
    .runtimeDependencies(runtimeDependencies)
    .providers(storageProvider, transportProvider)
    .defaultProviderBindings(bindings)
    .build()

dataLoom.initialize()
val result = dataLoom.synchronize(request)
dataLoom.shutdown()
```

The repository does not yet ship published V1 artifacts or complete production
reference providers for app storage and transport. The minimal example below
therefore uses public `dataloom-testing` fixtures to let you verify wiring and
result handling without inventing sample-only provider code. Replace those
fixtures with your app's real providers before treating the integration as more
than an evaluation or test harness.

## Current quickstart boundary

| Area | Current in this walkthrough | Still required for V1 |
|---|---|---|
| Facade assembly | `DataLoomBuilder`, `DataLoom.initialize()`, and direct `synchronize(request)` are implemented and verified in source | Full strategy-qualified product behavior across native Android, KMP Android, and KMP iOS |
| Providers | Real public SPI types for `StorageProvider`, `TransportProvider`, `QueueProvider`, `SchedulerProvider`, and `ConnectivityProvider` | Production reference storage/transport implementations and full platform qualification |
| Sample backing | `dataloom-testing` in-memory/scripted fixtures | Release-grade providers, durability, auth, retry/circuit, and platform lifecycle integration |

## Add the modules from this source checkout

From a composite build or another module inside this repository:

```kotlin
implementation(project(":dataloom-model"))
implementation(project(":dataloom-provider-api"))
implementation(project(":dataloom-api"))
implementation(project(":dataloom-runtime"))
implementation(project(":dataloom-testing")) // evaluation or tests only
implementation(libs.kotlinx.coroutines.core)
```

> [!IMPORTANT]
> `dataloom-testing` is intentionally test-only. Do not ship
> `InMemoryStorageProvider`, `ScriptedTransportProvider`,
> `FixedDataLoomClock`, or constant ID generators in a release app.

## First end-to-end synchronization call

The following sample compiles against the current repository and is mirrored by
`/runtime-external-consumer/src/commonMain/kotlin/io/dataloom/consumer/GettingStartedExternalConsumerProbe.kt`.

```kotlin
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.testing.identifier.ConstantIdentifierGenerator
import io.dataloom.testing.storage.InMemoryStorageProvider
import io.dataloom.testing.time.FixedDataLoomClock
import io.dataloom.testing.transport.ScriptedTransportProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun runGettingStartedSync(): String {
    val storageProvider = InMemoryStorageProvider()
    val transportProvider = ScriptedTransportProvider().apply {
        enqueuePullResult(ProviderOperationResult.Success(PullChangesResult.NoChanges()))
    }

    val bindings = SynchronizationProviderBindings(
        storageProviderId = storageProvider.descriptor.id,
        transportProviderId = transportProvider.descriptor.id,
    )

    val dataLoom = DataLoomBuilder()
        .runtimeDependencies(gettingStartedRuntimeDependencies())
        .providers(storageProvider, transportProvider)
        .defaultProviderBindings(bindings)
        .build()

    val initializeResult: ProviderLifecycleResult = dataLoom.initialize()
    if (initializeResult != ProviderLifecycleResult.InitializeSuccess) {
        return "Initialization did not complete: $initializeResult"
    }

    val execution = try {
        dataLoom.synchronize(gettingStartedRequest())
    } finally {
        withContext(NonCancellable) {
            dataLoom.shutdown()
        }
    }

    return when (execution) {
        is SynchronizationExecutionResult.Executed -> when (val result = execution.result) {
            is SynchronizationResult.Succeeded -> "Synchronization completed: ${result.summary}"
            is SynchronizationResult.PartiallySucceeded ->
                "Synchronization partially succeeded with ${result.errors.size} error(s)"
            is SynchronizationResult.Failed -> "Synchronization failed: ${result.error.code}"
            is SynchronizationResult.Cancelled -> "Synchronization cancelled at ${result.completedAt}"
            is SynchronizationResult.Skipped -> "Synchronization skipped: ${result.reason}"
        }

        is SynchronizationExecutionResult.Rejected ->
            "Synchronization rejected: ${execution.reason}"
    }
}

private fun gettingStartedRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
    clock = FixedDataLoomClock(DataLoomInstant(1_000L)),
    identifiers = RuntimeIdentifierGenerators(
        synchronizationEventIds = ConstantIdentifierGenerator(SynchronizationEventId("event-001")),
        queueEntryIds = ConstantIdentifierGenerator(QueueEntryId("queue-001")),
        queueLeaseIds = ConstantIdentifierGenerator(QueueLeaseId("lease-001")),
        conflictIds = ConstantIdentifierGenerator(ConflictId("conflict-001")),
    ),
)

private fun gettingStartedRequest(): SynchronizationRequest = SynchronizationRequest(
    workflowId = WorkflowId("contacts"),
    sessionId = SynchronizationSessionId("session-001"),
    direction = SynchronizationDirection.PULL,
    mode = SynchronizationMode.DELTA,
    context = ExecutionContext(
        executionId = ExecutionId("execution-001"),
        correlationId = CorrelationId("correlation-001"),
    ),
)
```

What this sample proves today:

1. You can register providers and explicit bindings.
2. You can build `DataLoom` through `DataLoomBuilder`.
3. You can initialize the facade, execute one direct synchronization call, and
   read the typed result.
4. You can do that from public API surface only.

What it does **not** prove:

- full offline-first, remote-first, cache-first, network-only, hybrid, or
  adaptive runtime qualification;
- release-ready Android or iOS app wiring;
- durable queue/retry/circuit behavior; or
- production storage/transport semantics.

## What you need to bring

- [ ] A [`StorageProvider`](./api/storage-provider.md) for local change reads,
      inbound apply, and checkpoint ownership. In a brownfield Android app, this
      usually wraps your existing Room database or repository boundary rather
      than replacing it.
- [ ] A [`TransportProvider`](./api/transport-provider.md) for your server
      protocol and authentication handoff. Keep HTTP/Ktor/GraphQL details behind
      this boundary. For JVM/Android Retrofit stacks, the in-tree reference
      module is [`dataloom-transport-retrofit`](./android/retrofit-transport-provider.md).
- [ ] Optionally, a [`QueueProvider`](./api/queue-provider.md) when you need
      durable queued work. The current in-tree Android adapter is
      [`dataloom-queue-room`](./android/room-queue-provider.md).
- [ ] Optionally, a [`SchedulerProvider`](./api/scheduler-provider.md) for
      platform scheduling. The current in-tree Android adapter is
      [`dataloom-scheduler-workmanager`](./android/workmanager-scheduler.md).
- [ ] Optionally, a [`ConnectivityProvider`](./api/connectivity-provider.md) for
      preflight network state. The current in-tree Android adapter is
      [`dataloom-connectivity-android`](./android/connectivity-provider.md).

**Current note:** the repository does **not** yet contain a reference Room
`StorageProvider` or the Ktor/GraphQL/gRPC transport reference modules. The
Retrofit reference is available for JVM/Android only. For the remaining
transport technologies, continue to use your own adapters behind the public
SPI until those sibling references land.

## Existing Android app: add DataLoom incrementally

For a brownfield Android app, keep the first integration narrow:

1. Pick one workflow such as contacts sync or one cache refresh path.
2. Wrap your existing Room DAOs or repository layer behind
   [`StorageProvider`](./api/storage-provider.md). DataLoom should own
   synchronization orchestration, not your domain schema or business truth.
3. Wrap one remote endpoint behind [`TransportProvider`](./api/transport-provider.md).
4. Start with direct `initialize()` + `synchronize(request)` calls.
5. Add `QueueProvider`, `SchedulerProvider`, and `ConnectivityProvider` only
   when that workflow needs durable background execution or preflight checks.

Today, `dataloom-queue-room` can supply the queue side of that setup on
Android. It does **not** replace your domain database or become a general
storage adapter for your app entities.

## New app: start with the runtime shape you want

For a greenfield app:

1. Decide whether your first workflow is direct-only or needs durable queueing.
2. Define one workflow ID, one request shape, and one storage/transport pair.
3. Keep authentication, domain repositories, payload mapping, and UI state in
   the app; pass only the synchronization-facing data through DataLoom.
4. Use `dataloom-testing` fixtures in early tests to prove orchestration and
   result handling before you wire real providers.
5. Add Android-specific adapters only where the host platform actually needs
   them.

If you later move from a direct call to queued/background execution, the same
provider-binding model still applies; you add queue, scheduler, and
connectivity roles rather than rewriting the facade entry point.

## Next steps

- [Synchronization strategy guide](./strategies/README.md)
- [Android integration guide](./android/README.md)
- [Testing toolkit](./testing/testing-toolkit.md)
- [DataLoom facade](./api/dataloom-facade.md)
