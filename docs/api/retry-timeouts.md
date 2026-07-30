# Retry timeout boundaries

> **Status:** Partial V1 runtime slice. Independent timeout configuration and
> workflow-deadline coordination exist; production executors, provider/policy
> wiring, and platform qualification remain incomplete.

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

## Safety rules

- Timeouts are non-negative `SchedulingDelay` values.
- Timeout-kind names are stable; ordinal persistence is prohibited.
- Caller cancellation must propagate and must not be converted into a timeout
  result.
- The timeout executor must interrupt or cancel the operation when its selected
  duration expires.
- Runtime enforcement must produce bounded, redaction-safe diagnostics.
- Unsupported platform behavior must be explicit rather than silently omitted.

## Remaining implementation

DataLoom still requires production `RetryTimeoutExecutor` implementations and
assembly for connection, request, idle, provider, policy, and workflow
operations. Canonical provider/error mapping, queue and scheduler integration,
restart propagation, and native Android/KMP Android/KMP iOS qualification remain
mandatory before FR-RETRY-006 is complete.
