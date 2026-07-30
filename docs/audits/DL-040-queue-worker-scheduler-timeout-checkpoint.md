# DL-040 queue-worker scheduler-timeout assembly checkpoint

## Decision

This checkpoint advances FR-RETRY-006 by assembling the production cooperative
provider-timeout boundary into the real queue-worker wake-up path. It does not
complete timeout separation or DL-040.

## Implemented behavior

- `QueueWorkerConfiguration` has an optional `schedulerProviderTimeout`.
- The default is null, preserving direct scheduler invocation for existing
  source callers.
- `QueueWorkerCoordinator` structurally wraps the supplied scheduler only when a
  timeout is configured.
- Construction performs no provider operation, clock read, coroutine launch, or
  scheduling work.
- A zero timeout prevents the scheduler delegate from being invoked.
- A positive timeout cancels a cooperative in-flight scheduler call.
- Successful scheduler results and canonical delegate failures are preserved.
- Timeout expiry is reported as `SchedulerFailed` with
  `SCHEDULER_PROVIDER_TIMEOUT`.
- Durable queue transitions completed before scheduling are never rolled back.
- Caller cancellation propagates and does not become a timeout result.
- No wake-up plan means no scheduler call and no provider-timeout clock read.

## Evidence

Common tests cover:

- default null compatibility;
- value/copy semantics for configured timeout;
- zero-timeout no-invocation behavior;
- bounded successful scheduling;
- historical null-timeout scheduling;
- caller cancellation during scheduling;
- durable completion preservation after timeout or cancellation; and
- absence of timeout clock reads when no wake-up is required.

The external consumer compiles the new configuration on JVM, `iosArm64`,
`iosSimulatorArm64`, and `iosX64`. Exact JVM and Kotlin-Native ABI baselines are
required on the final review head.

## Safety boundary

The timeout applies only to the queue worker's follow-up
`SchedulerProvider.schedule` invocation. It is not silently reused for queue
acquisition, queue transitions, retry policy, connection, request, idle, or
workflow execution.

Coroutine cancellation remains cooperative. Blocking implementations without a
cancellation checkpoint need a platform-specific hard-interruption adapter.

## Remaining work

- protect queue provider acquisition and transitions with explicit circuit and
  provider-timeout policy;
- assemble circuit permission into queue, transport, storage, retry, and
  strategy execution;
- implement protocol connection/request/idle enforcement;
- persist workflow-start evidence across queue/restart boundaries;
- complete events, metrics, logs, tracing, authorization, and audit; and
- pass native Android, KMP Android, and KMP iOS end-to-end qualification.
