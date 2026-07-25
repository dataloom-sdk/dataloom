# Scripted and Recording Utilities (DL-035)

## Scripted utilities

### `ScriptedTransportProvider`

Scripts push and pull results independently. Exhaustion fails fast so tests do
not silently continue with unexpected behaviour.

### `ScriptedRetryPolicy`

Dequeues retry decisions in order and optionally falls back to a final decision
when the script is exhausted.

### `ScriptedConflictDetector` / `ScriptedConflictResolver`

Provide deterministic conflict outcomes without runtime I/O.

## Recording utilities

### `TestProviderLifecycleController`

Centralizes provider lifecycle recording and state transitions for reusable test
fakes.

### `RecordingSchedulerProvider`

Records scheduling requests without executing work.

### `RecordingSynchronizationObserver`

Records synchronization events in order and can delegate to a callback for
additional assertions.
