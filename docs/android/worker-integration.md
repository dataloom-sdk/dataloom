# Worker Integration (DL-037)

The `dataloom-scheduler-workmanager` module provides `DataLoomCoroutineWorker`
and `DataLoomWorkerFactory` for integrating DataLoom queue processing with
AndroidX WorkManager.

## Overview

```
WorkManager triggers DataLoomCoroutineWorker
    → DataLoomCoroutineWorker constructs QueueWorkerRunRequest
    → Calls DataLoomQueueWorker.run(request)
    → Maps QueueWorkerRunResult to WorkManager Result
```

## DataLoomCoroutineWorker

`DataLoomCoroutineWorker` is a `CoroutineWorker` that bridges WorkManager
execution to the DataLoom `DataLoomQueueWorker` contract. It runs one bounded
queue-worker cycle per invocation.

**What it does:**
- Constructs a `QueueWorkerRunRequest` from injected parameters.
- Calls `DataLoomQueueWorker.run(request)` once.
- Maps `QueueWorkerRunResult` to `ListenableWorker.Result`.

**What it does NOT do:**
- Does not start or stop the DataLoom runtime.
- Does not loop until the queue is empty.
- Does not duplicate the DataLoom retry state machine.
- Does not reference a global DataLoom singleton.

## DataLoomWorkerFactory

WorkManager's default `WorkerFactory` uses reflection to instantiate workers
from their class names. `DataLoomCoroutineWorker` requires constructor
parameters that cannot be injected by the default factory.

`DataLoomWorkerFactory` handles this by matching the requested worker class
name against `DataLoomCoroutineWorker` and supplying the injected parameters.
For all other class names, it returns `null`, allowing WorkManager to delegate
to the next factory in the chain.

## WorkManager Configuration

Configure WorkManager to use `DataLoomWorkerFactory` via `WorkManager.initialize()`:

```kotlin
class MyApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(buildWorkerFactory())
            .build()

    private fun buildWorkerFactory(): WorkerFactory {
        return DataLoomWorkerFactory(
            queueWorker = dataLoom.queueWorker(),     // from DataLoom facade
            consumerId = QueueConsumerId("my-app"),
            leaseId = QueueLeaseId(UUID.randomUUID().toString()),
            acquiredAtMillis = System.currentTimeMillis(),
            leaseExpiresAtMillis = System.currentTimeMillis() + 60_000L,
            maxEntries = 10,
            recoverExpiredLeases = true,
        )
    }
}
```

Because `Configuration.Provider` is implemented by `Application`, WorkManager
initializes on-demand and uses the provided factory. The default
auto-initialization is suppressed by including WorkManager's
`work-runtime-ktx` artifact, which provides the necessary manifest merger rules.

## Result mapping

| `QueueWorkerRunResult` | WorkManager `Result` |
|---|---|
| `ProcessingCompleted` | `Result.success()` |
| `RecoveryFailed` | `Result.failure()` |

## Scheduling and retries

WorkManager retries are **not** used. The DataLoom durable queue state machine
handles retry scheduling independently. Setting `setBackoffCriteria` or
returning `Result.retry()` would create a duplicate retry mechanism.

When the `DataLoomQueueWorker` determines that further work is needed, it
schedules a follow-up work item via the `SchedulerProvider`. WorkManager
receives only `Result.success()` or `Result.failure()`.
