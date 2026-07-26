# Android Platform (DL-037)

DataLoom provides three independently consumable Android integration modules
that implement the platform-independent provider contracts defined in the
shared KMP modules.

## Modules

| Module | Description |
|---|---|
| `dataloom-connectivity-android` | Android `ConnectivityProvider` using `ConnectivityManager` |
| `dataloom-scheduler-workmanager` | `SchedulerProvider` backed by AndroidX WorkManager, plus `CoroutineWorker` bridge |
| `dataloom-queue-room` | `QueueProvider` backed by AndroidX Room with transactional acquisition |

## Design principles

- **Optional and independently consumable.** An application using only the
  Room queue provider does not need WorkManager or the Android connectivity
  module. There are no forced transitive dependencies between these modules.
- **No singleton or global state.** The worker bridge uses explicit constructor
  injection via `DataLoomWorkerFactory`. No static DataLoom instance is
  referenced from worker code.
- **No automatic initialization.** None of the modules start, stop, or
  configure the DataLoom runtime automatically.
- **Dispatchers.IO for all database and blocking calls.** Room operations never
  run on the main thread.
- **CancellationException propagates normally.** No module suppresses or
  converts coroutine cancellation to a failure result.

## Module boundaries

```
Android provider modules
    → dataloom-runtime / dataloom-core / dataloom-api
```

Shared production modules must not depend on Android modules. The dependency
direction is strictly one-way.

### dataloom-connectivity-android

- May depend on: DataLoom API contracts, Android framework connectivity APIs,
  minimal AndroidX Core APIs.
- Must not depend on: Room, WorkManager, `dataloom-scheduler-workmanager`,
  `dataloom-queue-room`.

### dataloom-scheduler-workmanager

- May depend on: DataLoom scheduler contracts, DataLoom runtime queue-worker
  contracts, AndroidX WorkManager.
- Must not depend on: Room implementation details, `dataloom-queue-room`,
  `dataloom-connectivity-android`.

### dataloom-queue-room

- May depend on: DataLoom queue contracts, Room, SQLite APIs required by Room.
- Must not depend on: WorkManager, `dataloom-scheduler-workmanager`,
  `dataloom-connectivity-android`.

## Getting started

Add only the modules you need to your application's `build.gradle.kts`:

```kotlin
// Optional: Android connectivity check
implementation(project(":dataloom-connectivity-android"))

// Optional: WorkManager scheduling
implementation(project(":dataloom-scheduler-workmanager"))

// Optional: Room queue persistence
implementation(project(":dataloom-queue-room"))
```

## Documentation

- [Connectivity Provider](./connectivity-provider.md)
- [WorkManager Scheduler](./workmanager-scheduler.md)
- [Room Queue Provider](./room-queue-provider.md)
- [Worker Integration](./worker-integration.md)
- [Security and R8](./security-and-r8.md)
