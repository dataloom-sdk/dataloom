# DataLoom WorkManager Scheduler — consumer R8/ProGuard rules.
# Applied to consuming applications automatically by the Android build tools.

# Preserve the public provider and worker factory classes.
-keep class io.dataloom.scheduler.workmanager.WorkManagerSchedulerProvider { *; }
-keep class io.dataloom.scheduler.workmanager.DataLoomCoroutineWorker { *; }
-keep class io.dataloom.scheduler.workmanager.DataLoomWorkerFactory { *; }

# WorkManager requires worker classes to be kept for reflection-based instantiation
# when not using a custom WorkerFactory. DataLoom always uses DataLoomWorkerFactory
# for explicit injection, but keep the class name stable as a safeguard.
-keepnames class io.dataloom.scheduler.workmanager.DataLoomCoroutineWorker
