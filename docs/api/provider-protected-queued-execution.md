# Provider-protected queued execution

## Status

Partial V1 subsystem. This slice executes one acquired queue entry through the
builder-configured protected synchronization capability and preserves the exact
admission, provider execution, and circuit-recording evidence. Circuit-aware
queue transition and worker adoption remain separate reviewed slices.

## Explicit bindings

`DataLoomProtectedSynchronization` supports both:

```kotlin
synchronize(request)
synchronize(request, bindings)
```

Queued work uses the explicit overload. The exact
`SynchronizationProviderBindings` resolved from the durable queue entry are
forwarded unchanged. There is no fallback to builder default bindings.

## Entry execution result

`ProviderProtectedQueueEntryExecutionResult` contains:

- the exact acquired `QueueEntryId`;
- one `QueueEntryExecutionOutcome` requesting a later durable transition;
- the exact `ProviderProtectedSynchronizationExecutionResult` when protected
  synchronization was reached;
- a defensive ordered snapshot of `ProviderProtectionOperationEvidence`.

A null protected execution result means local work resolution or persisted
workflow deadline enforcement stopped before protected synchronization.

## Execution order

`ProviderProtectedQueuedSynchronizationExecutionHandler` performs:

1. Resolve the exact acquired queue entry to queued synchronization work.
2. Enforce immutable persisted workflow deadline evidence when present.
3. Execute protected synchronization with the exact request and bindings.
4. Preserve admission or execution evidence.
5. Map the terminal synchronization result to one queue outcome.
6. Apply fail-closed retry evaluation for failed or partial results.

The handler does not access a queue provider, submit a transition, schedule
work, or automatically invoke itself again.

## Safety behavior

- Resolver rejection invokes no protected synchronization.
- Expired workflow deadline invokes no protected synchronization.
- Circuit rejection is preserved as an exact protected admission/execution
  result and provider evidence remains empty when no provider ran.
- Provider execution followed by unconfirmed circuit recording remains visible
  through the operation evidence.
- The corresponding canonical error has `Recoverability.UNKNOWN`, so central
  retry protection stops automatic retry even when an application policy would
  otherwise request it.
- Retry-attempt integer exhaustion fails closed instead of overflowing.
- Connectivity rejection can be mapped to deterministic deferral without
  fabricating provider evidence.
- Caller cancellation and unexpected exceptions propagate unchanged.

## Diagnostics

The public result diagnostic string includes only:

- queue entry ID;
- outcome class;
- whether protected execution was reached;
- provider evidence count.

It does not render provider return values, payloads, credentials, headers,
checkpoint contents, circuit state records, exception text, or arbitrary
metadata.

## Remaining integration

The next slice must integrate this handler into circuit-aware bounded queue
processing and worker/facade assembly while preserving per-entry provider
execution evidence alongside queue acquisition and transition evidence.
