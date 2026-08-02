# Provider-protected queued synchronization

`ProviderProtectedQueuedSynchronizationRuntime` is an additive queue invocation boundary that combines:

1. Application-owned `QueuedSynchronizationWorkResolver` resolution.
2. Immutable persisted workflow-deadline enforcement.
3. Explicit `DataLoomProtectedSynchronization` execution using the exact queued provider bindings.
4. Preservation of the complete provider/circuit evidence returned by the protected synchronization facade.

The result keeps four outcomes separate:

- Local work-resolution rejection.
- Persisted workflow-timeout rejection.
- Lifecycle, binding, pipeline, or connectivity admission rejection.
- Executed protected synchronization with ordered storage/transport evidence.

This runtime deliberately does not map an executed protected result into a legacy queue transition. A provider call can complete while the later circuit-state update remains unconfirmed. Collapsing that evidence into a plain queue outcome could authorize unsafe replay. A subsequent worker-integration slice must preserve `ProviderProtectedSynchronizationResult` while selecting exactly one queue transition.

The default protected synchronization overload is never used. Queued work always supplies its accepted explicit provider bindings.

Construction performs no resolver, provider, circuit-store, clock, timeout, I/O, identifier, or coroutine activity. Caller cancellation and unexpected exceptions propagate unchanged.
