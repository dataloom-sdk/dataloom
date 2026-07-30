# DL-040 coroutine provider-timeout checkpoint

## Decision

This checkpoint advances FR-RETRY-006 but does not complete it.

The repository now has a production Kotlin Multiplatform timeout executor for
cooperative suspending operations and an explicit scheduler-provider decorator
that enforces the configured provider-invocation timeout across lifecycle,
schedule, and cancellation calls.

## Implemented evidence

- `CoroutineRetryTimeoutExecutor` uses structured coroutine timeout and
  cancellation without creating a scope or selecting a dispatcher.
- Nullable successful values are preserved through a non-null completion box.
- A zero timeout prevents operation invocation.
- Expiry cancels the child operation and returns the exact timeout kind and
  duration.
- Caller cancellation propagates unchanged.
- A timeout exception created by a nested operation is not misclassified as the
  executor's own timeout.
- `TimeoutEnforcingSchedulerProvider` preserves the delegate descriptor.
- Successful and canonical failed provider results are returned unchanged.
- Timeout expiry maps to bounded `SCHEDULER_PROVIDER_TIMEOUT` evidence.
- Initialization, health, schedule, cancellation, and close use the same
  provider-timeout boundary.
- External JVM and Kotlin/Native consumers compile the public executor and
  decorator.

## Safety boundary

Coroutine cancellation is cooperative. The implementation does not claim to
hard-interrupt arbitrary blocking or CPU-bound code that never reaches a
cancellation checkpoint. Platform-specific executors remain required where hard
interruption is part of the approved behavior.

The scheduler decorator does not infer workflow start time and does not reuse a
provider timeout as a connection, request, idle, policy, or workflow timeout.

## Remaining FR-RETRY-006 work

- assemble timeout enforcement into transport, storage, queue, retry-policy, and
  synchronization execution;
- implement protocol-specific connection, request, and idle timeout adapters;
- carry durable workflow-start evidence across queueing, retry, restart, and
  relaunch;
- define safe policy-timeout enforcement for the synchronous `RetryPolicy`
  contract;
- add platform-specific hard-interruption behavior where cooperative
  cancellation is insufficient; and
- pass the native Android, KMP Android, and KMP iOS timeout matrix.

This checkpoint must not be used to claim that timeout support or DL-040 is
complete.
