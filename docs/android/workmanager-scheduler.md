# WorkManager scheduler provider

> **Audience:** Android developers scheduling DataLoom queue-worker wake-ups
> **Purpose:** Document the exact `ScheduleRequest` to WorkManager mapping and
> current integration limits
> **Status:** Implemented scheduler foundation; not a complete retry or
> strategy engine

[← Android overview](README.md) ·
[Worker integration](worker-integration.md) ·
[Connectivity provider](connectivity-provider.md)

`WorkManagerSchedulerProvider` implements the shared `SchedulerProvider`
contract with unique, one-time AndroidX WorkManager requests.

## Module

```kotlin
implementation(project(":dataloom-scheduler-workmanager"))
```

This project dependency is for the source checkout. Published V1 coordinates
are not available yet.

## Mapping

| DataLoom value | WorkManager value |
|---|---|
| `ScheduleRequest.id.value` | Unique work name |
| `ExistingSchedulePolicy.KEEP` | `ExistingWorkPolicy.KEEP` |
| `ExistingSchedulePolicy.REPLACE` | `ExistingWorkPolicy.REPLACE` |
| `ConnectivityRequirement.NONE` | `NetworkType.NOT_REQUIRED` |
| `ConnectivityRequirement.AVAILABLE` | `NetworkType.CONNECTED` |
| `ConnectivityRequirement.UNMETERED` | `NetworkType.UNMETERED` |
| `ScheduleConstraints.requiresCharging` | `Constraints.setRequiresCharging` |
| `SchedulingDelay.milliseconds` | Initial delay in milliseconds |

The provider does not clamp a negative delay; `SchedulingDelay` rejects it at
construction.

## Usage

```kotlin
val scheduler = WorkManagerSchedulerProvider(context)

val result = scheduler.schedule(
    ScheduleRequest(
        id = ScheduleId("dataloom-queue-worker"),
        delay = SchedulingDelay.ZERO,
        constraints = ScheduleConstraints(
            connectivity = ConnectivityRequirement.AVAILABLE,
        ),
        existingPolicy = ExistingSchedulePolicy.KEEP,
    ),
)
```

Both `schedule` and `cancel` wait for WorkManager's asynchronous operation to
reach a successful terminal state before returning
`ProviderOperationResult.Success`. Acceptance means that WorkManager accepted
the request; it does not mean synchronization started or completed.

## Worker and retry boundary

Every request currently targets `DataLoomCoroutineWorker`. The implementation
does not serialize `ScheduleRequest.synchronizationRequest` into WorkManager
input data. The worker instead obtains a fresh `QueueWorkerRunRequest` from the
injected factory.

Treat the current module as a queue-worker wake-up bridge. Direct,
per-`SynchronizationRequest` retry transport has not been qualified.
WorkManager retry is deliberately not used as a second retry mechanism;
DataLoom policy, durable queue state, and follow-up scheduling own that
decision.

See [Worker integration](worker-integration.md) for the required manifest
change, custom `WorkerFactory`, and `Configuration.Provider`.

## Current limits

- The provider creates one-time work only.
- It does not initialize DataLoom providers or restore host configuration.
- It does not persist DataLoom queue records.
- It does not implement offline-first, remote-first, cache-first,
  network-only, hybrid, or adaptive behavior.
- Process recreation, background execution, and end-to-end consumer behavior
  remain V1 qualification work.

## Related documentation

- [Scheduler provider contract](../api/scheduler-provider.md)
- [Worker integration](worker-integration.md)
- [Background execution boundaries](../architecture/background-execution-boundaries.md)
