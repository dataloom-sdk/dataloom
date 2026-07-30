# Retry timeout boundaries

> **Status:** Contract slice. Independent timeout configuration exists; runtime enforcement and platform qualification remain in progress.

DataLoom treats timeout concepts as separate policy boundaries. A connection timeout must not be reused as a request, idle, provider, policy, or workflow timeout.

`RetryTimeoutConfiguration` supports six independently optional limits:

- `connectionTimeout`: establishing a remote connection;
- `requestTimeout`: one request/response exchange;
- `idleTimeout`: no observable transfer progress;
- `providerTimeout`: one provider invocation;
- `policyTimeout`: one retry-policy evaluation; and
- `workflowTimeout`: the complete synchronization workflow.

At least one boundary must be configured. `timeoutFor(RetryTimeoutKind)` returns the exact configured value and never falls back to another timeout.

## Safety rules

- Timeouts are non-negative `SchedulingDelay` values.
- Timeout-kind names are stable; ordinal persistence is prohibited.
- Cancellation must continue to propagate and must not be converted into timeout failure.
- Runtime enforcement must produce bounded, redaction-safe diagnostics.
- Platform adapters may implement a boundary differently, but unsupported behavior must be explicit rather than silently omitted.

## Remaining implementation

The next steps are timeout execution wrappers, canonical timeout reason/error mapping, workflow deadline propagation, provider and policy enforcement, queue/scheduler behavior, and JVM/Android/iOS qualification.
