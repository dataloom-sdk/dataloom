# WorkManager Scheduler Provider (DL-037)

`WorkManagerSchedulerProvider` implements DataLoom's `SchedulerProvider`
contract using AndroidX WorkManager.

## Module

`dataloom-scheduler-workmanager`

## Dependency

```kotlin
implementation(project(":dataloom-scheduler-workmanager"))
```

## Schedule mapping

| DataLoom value | WorkManager value |
|---|---|
| `ExistingSchedulePolicy.KEEP` | `ExistingWorkPolicy.KEEP` |
| `ExistingSchedulePolicy.REPLACE` | `ExistingWorkPolicy.REPLACE` |
| `ConnectivityRequirement.NONE` | `NetworkType.NOT_REQUIRED` |
| `ConnectivityRequirement.AVAILABLE` | `NetworkType.CONNECTED` |
| `ConnectivityRequirement.UNMETERED` | `NetworkType.UNMETERED` |

`SchedulingDelay.milliseconds` is passed to
`OneTimeWorkRequest.Builder.setInitialDelay`. The DataLoom value type already
rejects negative delays; the provider does not silently clamp or rewrite it.

The stable unique work name is `ScheduleRequest.id.value`. A successful
provider result is returned only after WorkManager confirms that the
asynchronous enqueue or cancellation operation completed successfully. It does
not mean that synchronization has started or completed.

## Usage

```kotlin
val provider = WorkManagerSchedulerProvider(context)

val receipt = provider.schedule(
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

WorkManager retry is not used as a second retry mechanism. DataLoom's durable
queue owns retry decisions and follow-up scheduling.

## Worker integration

See [Worker Integration](./worker-integration.md) for the required custom
`WorkerFactory`, manifest change, and `Configuration.Provider` setup.
