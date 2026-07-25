# In-Memory Providers (DL-035)

## Overview

The DL-035 in-memory providers support tests that need stable, inspectable
provider state without real storage, transport, connectivity APIs, or queue
infrastructure.

## Providers

### `InMemoryStorageProvider`

- Records outbound read, inbound apply, acknowledgement, and checkpoint calls
- Stores checkpoints in memory
- Supports scripted storage operation results
- Supports checkpoint read and write failure injection

### `InMemoryQueueProvider`

- Preserves enqueue order with `LinkedHashMap`
- Implements enqueue, acquire, complete, reschedule, fail, cancel, and expired
  lease recovery transitions
- Allows cancellation of leased entries in tests for simpler orchestration
  scenarios
- Exposes snapshot helpers for entry IDs and states

### `MutableConnectivityProvider`

- Returns a mutable connectivity snapshot
- Records connectivity checks
- Can inject a constant failure result

## Resetting state

Use `clearRecordings()` to keep provider state while removing request history.
Use `resetState()` to clear scripted results and in-memory state.
