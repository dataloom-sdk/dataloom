# Retry timeout boundaries

> **Status:** Partial V1 runtime slice. Independent timeout configuration,
> workflow-deadline coordination, a production cooperative coroutine executor,
> and explicit scheduler-provider timeout protection exist. Complete provider,
> policy, workflow, platform, and restart assembly remains incomplete.

DataLoom treats timeout concepts as separate policy boundaries. A connection
timeout must not be reused as a request, idle, provider, policy, or workflow
timeout.

`RetryTimeoutConfiguration` supports six independently optional limits:

- `connectionTimeout`: establishing a remote connection;
- `requestTimeout`: one request/response exchange;
- `idleTimeout`: no observable transfer progress;
- `providerTimeout`: one provider invocation;
- `policyTimeout`: one retry-policy evaluation; and
- `workflowTimeout`: the complete synchronization workflow.

At least one boundary must be configured. `timeoutFor(RetryTimeoutKind)` returns
the exact configured value and never falls back to another boundary.

## Coordinator behavior

`RetryTimeoutCoordinator` accepts an operation kind and optional persisted
workflow start time.

- When neither the requested boundary nor a usable workflow deadline is
  configured, the operation executes directly.
- A configured workflow timeout is enforced whenever `workflowStartedAt` is
  supplied, even if the requested connection/request/idle/provider/policy
  boundary is unconfigured.
- An expired workflow is rejected before invoking the timeout executor.
- A clock observation earlier than the supplied workflow start fails closed.
- When both limits exist, the shorter duration is selected.
- The selected request is classified as `WORKFLOW` when the remaining workflow
  window is less than or equal to the requested boundary. Otherwise it retains
  the requested boundary kind.
- The absolute workflow deadline is propagated to the executor so platform
  integrations can preserve deadline-aware diagnostics.

A workflow timeout is not silently started at coordinator invocation. Callers
that need complete-workflow enforcement must carry the original
`workflowStartedAt` evidence through retry, queue, and restart boundaries.

## Coroutine executor

`CoroutineRetryTimeoutExecutor` is the production Kotlin Multiplatform executor
for cooperative suspending operations. It:

- runs in the caller's coroutine context;
- creates no independent `CoroutineScope`;
- selects no dispatcher;
- cancels its child operation when the selected timeout expires;
- returns `RetryTimeoutExecutionResult.TimedOut` only for its own timeout;
- preserves nullable completed values;
- propagates caller cancellation; and
- does not swallow timeout exceptions created by nested operations.

Coroutine cancellation is cooperative. An operation that blocks without a
suspension or another cancellation checkpoint cannot be hard-interrupted by this
executor. Such operations require an explicit platform-specific executor rather
than a misleading common-code timeout claim.

## Scheduler-provider integration

`TimeoutEnforcingSchedulerProvider` decorates an existing `SchedulerProvider`
and applies the configured `PROVIDER` timeout to:

- initialization;
- health evaluation;
- scheduling;
- cancellation; and
- close.

```kotlin
val timeoutExecutor = CoroutineRetryTimeoutExecutor()
val timeoutCoordinator = RetryTimeoutCoordinator(
    configuration = RetryTimeoutConfiguration(
        providerTimeout = SchedulingDelay(5_000L),
    ),
    clock = clock,
    executor = timeoutExecutor,
)
val boundedScheduler = TimeoutEnforcingSchedulerProvider(
    delegate = schedulerProvider,
    timeoutCoordinator = timeoutCoordinator,
)

val orchestrator = SynchronizationRetryOrchestrator(
    retryPolicy = retryPolicy,
    schedulerProvider = boundedScheduler,
    configuration = retrySchedulingConfiguration,
)
```

Successful results and canonical delegate failures are preserved exactly.
Provider-timeout expiry becomes the bounded recoverable error code
`SCHEDULER_PROVIDER_TIMEOUT`. Caller cancellation and unexpected programming
exceptions continue to propagate.

The decorator does not infer workflow start time and does not silently apply
connection, request, idle, or policy limits. Complete workflow enforcement must
remain an explicit higher-level assembly concern.

## Safety rules

- Timeouts are non-negative `SchedulingDelay` values.
- Timeout-kind names are stable; ordinal persistence is prohibited.
- Caller cancellation must propagate and must not be converted into a timeout
  result.
- Runtime enforcement must produce bounded, redaction-safe diagnostics.
- Unsupported or non-interruptible behavior must be explicit rather than
  silently omitted.

## Remaining implementation

DataLoom still requires:

- automatic timeout assembly in transport, storage, queue, retry-policy, and
  synchronization workflow paths;
- connection, request, and idle enforcement in protocol/platform adapters;
- a safe policy-timeout strategy for the synchronous non-blocking `RetryPolicy`
  contract;
- durable workflow-start propagation across queueing, retry, restart, and
  relaunch;
- platform-specific hard-interruption adapters where cooperative cancellation is
  insufficient; and
- native Android, KMP Android, and KMP iOS end-to-end qualification.

FR-RETRY-006 remains partial until those boundaries and acceptance tests pass.
