# DataLoom Scheduler Provider (DL-012)

`dataloom-api` defines a platform-independent scheduler provider SPI that
allows DataLoom to request deferred synchronization execution without
depending directly on WorkManager, AlarmManager, or any other platform
scheduler.

---

## Contracts

### `ScheduleId`

**Package:** `io.dataloom.api.identifier`

Immutable value type identifying a single scheduling entry.

```kotlin
val id = ScheduleId("sync-schedule-001")
```

- Value must not be blank or whitespace-only.
- Valid input is preserved exactly as supplied.
- No normalization or automatic generation is applied.
- `toString()` returns the underlying value.
- Ownership: DataLoom runtime or host integration.

---

### `SchedulingDelay`

**Package:** `io.dataloom.api.scheduling`

Immutable relative delay expressed in milliseconds.

```kotlin
val delay = SchedulingDelay(30_000L)
val immediate = SchedulingDelay.ZERO
```

- `milliseconds` must be zero or greater.
- Zero means no minimum delay is requested.
- Negative values are rejected at construction.
- Construction does not read the clock, calculate an absolute timestamp,
  sleep, or delay execution.
- Represents a relative duration, not an absolute timestamp.

The platform scheduler interprets this delay as a minimum requested wait.
Actual execution may be deferred further by operating-system scheduling
policies or battery-optimization constraints.

---

### `ExistingSchedulePolicy`

**Package:** `io.dataloom.api.scheduling`

Closed set of policies for handling a `ScheduleRequest` when a schedule
with the same `ScheduleId` already exists in the platform scheduler.

| Value     | Semantics |
|-----------|-----------|
| `KEEP`    | Retain the existing schedule. The new request is ignored. |
| `REPLACE` | Remove the existing schedule and register the new request. |

The concrete `SchedulerProvider` implementation is responsible for mapping
these semantics to the underlying platform.

---

### `ScheduleConstraints`

**Package:** `io.dataloom.api.scheduling`

Immutable execution constraints for a scheduled synchronization request.

```kotlin
val constraints = ScheduleConstraints(
    connectivity = ConnectivityRequirement.AVAILABLE,
    requiresCharging = false,
)
```

| Property          | Type                     | Default                        |
|-------------------|--------------------------|--------------------------------|
| `connectivity`    | `ConnectivityRequirement` | `ConnectivityRequirement.NONE` |
| `requiresCharging` | `Boolean`               | `false`                        |
| `metadata`        | `DataLoomMetadata`       | `DataLoomMetadata.Empty`       |

- Construction does not query connectivity state, inspect charging state,
  or access platform APIs.
- Unsupported constraints must be reported by the concrete provider as a
  canonical `DataLoomError`.
- Metadata must not contain credentials, tokens, or personal data.

The following constraint options are explicitly deferred to future issues:

- Device idle
- Battery not low
- Storage not low
- Content URI triggers
- Exact alarms

---

### `ScheduleRequest`

**Package:** `io.dataloom.api.scheduling`

Immutable scheduling intent for a synchronization workflow.

```kotlin
val request = ScheduleRequest(
    id = ScheduleId("sync-schedule-001"),
    synchronizationRequest = syncRequest,
    delay = SchedulingDelay(60_000L),
    constraints = ScheduleConstraints(
        connectivity = ConnectivityRequirement.AVAILABLE,
    ),
    existingPolicy = ExistingSchedulePolicy.REPLACE,
)
```

| Property                  | Type                       | Default                    |
|---------------------------|----------------------------|----------------------------|
| `id`                      | `ScheduleId`               | required                   |
| `synchronizationRequest`  | `SynchronizationRequest`   | required                   |
| `delay`                   | `SchedulingDelay`          | `SchedulingDelay.ZERO`     |
| `constraints`             | `ScheduleConstraints`      | `ScheduleConstraints()`    |
| `existingPolicy`          | `ExistingSchedulePolicy`   | `ExistingSchedulePolicy.KEEP` |

- Construction does not schedule execution, enqueue synchronization,
  inspect platform state, or mutate the `SynchronizationRequest`.

---

### `ScheduleReceipt`

**Package:** `io.dataloom.api.scheduling`

Immutable confirmation that a `SchedulerProvider` accepted a
`ScheduleRequest`.

```kotlin
val receipt = ScheduleReceipt(id = ScheduleId("sync-schedule-001"))
```

| Property   | Type               | Default                  |
|------------|--------------------|--------------------------|
| `id`       | `ScheduleId`       | required                 |
| `metadata` | `DataLoomMetadata` | `DataLoomMetadata.Empty` |

A receipt confirms the provider registered the scheduling intent. It does
not guarantee that:

- The workflow has started
- The workflow completed successfully
- The platform will trigger the workflow under every operating-system
  condition

Platform-specific scheduler identifiers (such as WorkManager work IDs)
remain internal to the provider. `ScheduleReceipt` exposes only the
canonical DataLoom `ScheduleId`.

---

### `ScheduleCancellationRequest`

**Package:** `io.dataloom.api.scheduling`

Immutable request to cancel a previously scheduled synchronization.

```kotlin
val cancellation = ScheduleCancellationRequest(
    id = ScheduleId("sync-schedule-001"),
    context = executionContext,
)
```

| Property  | Type             | Default  |
|-----------|------------------|----------|
| `id`      | `ScheduleId`     | required |
| `context` | `ExecutionContext` | required |

- Construction does not cancel scheduled or running work.
- Cancellation of a scheduled entry does not automatically cancel a
  synchronization workflow that has already started.
- Runtime workflow cancellation semantics are deferred to a future issue.

---

### `SchedulerProvider`

**Package:** `io.dataloom.api.scheduling`

Platform-independent provider contract for scheduling deferred
synchronization execution.

```kotlin
public interface SchedulerProvider : DataLoomProvider {

    override val descriptor: ProviderDescriptor

    suspend fun schedule(
        request: ScheduleRequest,
    ): ProviderOperationResult<ScheduleReceipt>

    suspend fun cancel(
        request: ScheduleCancellationRequest,
    ): ProviderOperationResult<Unit>
}
```

- Descriptor type must be `ProviderType.SCHEDULER`.
- Does not expose WorkManager, Worker, AlarmManager, JobScheduler, Apple
  background-task, `CoroutineScope`, or platform-specific types.
- Does not select threads or dispatchers.
- Does not execute synchronization directly.
- Does not perform storage or transport operations.
- Does not implement retry policy.
- Preserves coroutine cancellation.

#### Thread safety

Implementations are responsible for documenting and enforcing their own
thread-safety guarantees.

#### Coroutine cancellation

Implementations must preserve coroutine cancellation and must not convert
cancellation exceptions into normal failures.

#### Error handling

- Platform failures must be mapped to canonical `DataLoomError` values.
- Unsupported constraints must return a canonical configuration or provider
  error.
- Platform exceptions must not escape through the public contract.

---

## Future WorkManager Boundary

A future `dataloom-workmanager` artifact may implement the following flow:

```text
DataLoom Runtime
      ↓
ScheduleRequest
      ↓
WorkManagerSchedulerProvider
      ↓
WorkManager
      ↓
DataLoom Android worker entry point
      ↓
Shared DataLoom runtime
```

That implementation may map:

| `ScheduleConstraints` property                | WorkManager equivalent     |
|-----------------------------------------------|----------------------------|
| `ConnectivityRequirement.AVAILABLE`           | connected network constraint |
| `ConnectivityRequirement.UNMETERED`           | unmetered network constraint |
| `requiresCharging = true`                     | charging constraint          |

The shared API must not expose WorkManager classes.

---

## Deferred Scheduling Features

The following are deferred to future issues:

- Periodic scheduling
- Cron-style scheduling
- Absolute timestamps
- Exact alarms
- Expedited execution
- Foreground execution
- Long-running workers
- Device-idle requirements
- Battery-low requirements
- Storage-low requirements
- Chained scheduling
- Schedule inspection
- Scheduled-work status observation
- Automatic rescheduling
- Platform reboot handling
- Runtime workflow cancellation
- Scheduler capability negotiation

---

## Kotlin Multiplatform Boundary

- Shared contracts remain in `dataloom-api`.
- Android scheduling is implemented in an Android-specific module.
- Apple scheduling requires a future Apple-specific adapter.
- KMP does not guarantee identical background execution semantics across
  platforms.
- Each platform provider documents its own limitations.
- Unsupported constraints must produce a canonical `DataLoomError`.
- Provider interfaces are preferred over forcing scheduler behavior
  through `expect`/`actual`.
