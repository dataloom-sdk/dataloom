# Testing Toolkit Overview (DL-035)

**Module:** `dataloom-testing`

## Overview

DL-035 extends the testing module with deterministic provider, policy, and
observer utilities for common tests. The toolkit remains multiplatform-friendly,
uses no external dependencies, and avoids platform-specific APIs.

## Included utilities

- `TestProviderLifecycleController`
- `InMemoryStorageProvider`
- `ScriptedTransportProvider`
- `InMemoryQueueProvider`
- `RecordingSchedulerProvider`
- `MutableConnectivityProvider`
- `ScriptedRetryPolicy`
- `ScriptedConflictDetector`
- `ScriptedConflictResolver`
- `RecordingSynchronizationObserver`

## Design goals

- Deterministic state transitions
- CommonMain compatibility
- No reflection or ServiceLoader usage
- No global mutable state
- Explicit recording and reset hooks for tests

## Typical usage

Use the utilities to script provider outcomes, inspect recorded requests, and
exercise runtime orchestration in unit or integration-style common tests.
