# WorkManager Scheduler Provider (DL-037)

`WorkManagerSchedulerProvider` implements the DataLoom `SchedulerProvider`
contract using AndroidX WorkManager.

## Module

`dataloom-scheduler-workmanager`

## Dependency

```kotlin
implementation(project(":dataloom-scheduler-workmanager"))
```

## Schedule mapping

### ExistingSchedulePolicy

| `ExistingSchedulePolicy` | `ExistingWorkPolicy` |
|---|---|
| `KEEP` | `ExistingWorkPolicy.KEEP` |
| `REPLACE` | `ExistingWorkPolicy.REPLACE` |

### ConnectivityRequirement

| `ConnectivityRequirement` | WorkManager `NetworkType` |
|---|---|
| `NONE` | `NetworkType.NOT_REQUIRED` |
| `AVAILABLE` | `NetworkType.CONNECTED` |
| `UNMETERED` | `NetworkType.UNMETERED` |

### SchedulingDelay

`SchedulingDelay.Relative` delays are applied to the WorkManager
`OneTimeWorkRequest` via `setInitialDelay`. The delay value is clamped to
avoid `Long` overflow.

### Work name

The stable unique WorkManager work name is derived from `ScheduleId.value`.
The same `ScheduleId` always maps to the same work name, ensuring that
`ExistingWorkPolicy.KEEP` and `ExistingWorkPolicy.REPLACE` behave correctly
across scheduling calls.

## Behaviour contract

- Enqueues at most once per `schedule()` call.
- Does not duplicate the retry mechanism between WorkManager retry and the
  DataLoom durable queue state machine.
- `ScheduleReceipt.scheduleId` is preserved and returned to the caller.

## Usage

```kotlin
val workManager = WorkManager.getInstance(context)
val provider = WorkManagerSchedulerProvider(workManager)

val receipt = provider.schedule(
    ScheduleRequest(
        scheduleId = ScheduleId("dataloom-queue-worker"),
        delay = SchedulingDelay.None,
        constraints = ScheduleConstraints(
            requiresNetwork = ConnectivityRequirement.AVAILABLE,
        ),
        existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
    )
)
```

## Worker integration

See [Worker Integration](./worker-integration.md) for configuring
`DataLoomWorkerFactory` with WorkManager's `Configuration`.
