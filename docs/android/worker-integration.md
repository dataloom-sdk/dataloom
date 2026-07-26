# Worker Integration (DL-037)

The `dataloom-scheduler-workmanager` module provides
`DataLoomCoroutineWorker` and `DataLoomWorkerFactory` for integrating a
DataLoom queue worker with AndroidX WorkManager.

## Overview

```text
WorkManager triggers DataLoomCoroutineWorker
    → QueueWorkerRunRequestFactory creates a fresh request
    → DataLoomQueueWorker.run(request) executes once
    → QueueWorkerRunResult maps to WorkManager Result
```

Each WorkManager invocation performs one bounded queue-worker cycle. The bridge
does not start the DataLoom runtime, loop until the queue is empty, add a second
retry state machine, or look up a global DataLoom singleton.

## Disable WorkManager's default initializer

A custom `WorkerFactory` requires custom WorkManager configuration. Remove only
WorkManager's AndroidX Startup initializer in the application manifest:

```xml
<manifest
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application>
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>
    </application>
</manifest>
```

Do not remove the whole `InitializationProvider` when the application uses
AndroidX Startup for other components.

## Supply DataLoomWorkerFactory

Implement `Configuration.Provider` on the application. WorkManager discovers
this configuration when the host calls `WorkManager.getInstance(context)`;
the application must not call `WorkManager.initialize()` itself.

```kotlin
class MyApplication : Application(), Configuration.Provider {

    private val dataLoom: DataLoom by lazy { buildDataLoom() }

    private val requestFactory = QueueWorkerRunRequestFactory {
        val nowMillis = System.currentTimeMillis()
        QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(
                acquireRequest = QueueAcquireRequest(
                    consumerId = QueueConsumerId("my-app"),
                    leaseId = QueueLeaseId(UUID.randomUUID().toString()),
                    acquiredAt = DataLoomInstant(nowMillis),
                    leaseExpiresAt = DataLoomInstant(nowMillis + 60_000L),
                    maxEntries = 10,
                ),
            ),
            recoveryRequest = ExpiredLeaseRecoveryRequest(
                currentTime = DataLoomInstant(nowMillis),
            ),
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                DataLoomWorkerFactory(
                    queueWorker = requireNotNull(dataLoom.queueWorker) {
                        "Configure queue-worker support before WorkManager starts."
                    },
                    requestFactory = requestFactory,
                ),
            )
            .build()
}
```

The request factory runs when the worker executes. It must generate a fresh
lease identifier and timestamps on every call. Set `recoveryRequest` to
`null` instead when the DataLoom queue-worker configuration disables
expired-lease recovery.

## Result mapping

| `QueueWorkerRunResult` | WorkManager `Result` |
|---|---|
| `ProcessingCompleted` | `Result.success()` |
| `RecoveryFailed` | `Result.failure()` |
| `ProcessingFailed` | `Result.failure()` |

## Scheduling and retries

The bridge never returns `Result.retry()`. DataLoom's durable queue owns retry
and rescheduling state. When another bounded cycle is useful, the queue-worker
coordinator requests a follow-up schedule through `SchedulerProvider`.
