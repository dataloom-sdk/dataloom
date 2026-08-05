# DL-039B cache-first direct remote direction matrix checkpoint

## Decision

The common runtime now executes all currently deterministic direct
storage/transport cache-first plans that require no refresh or durable
continuation:

| Direction | Required evidence | Ordered operations | Origin |
|---|---|---|---|
| `PUSH` | Connectivity available | `READ_LOCAL → PUSH_REMOTE` | `LOCAL` |
| `PULL` | Cache missing and connectivity available | `READ_CHECKPOINT → PULL_REMOTE → PERSIST_REMOTE` | `REMOTE` |
| `BIDIRECTIONAL` | Cache missing and connectivity available | `READ_LOCAL → PUSH_REMOTE → READ_CHECKPOINT → PULL_REMOTE → PERSIST_REMOTE` | `MIXED` |

Each direction reuses the canonical shared pipeline selected from
`SynchronizationPipelineRegistry`. Cache-first determines the immutable source,
ordering, persistence, capability, and origin plan; it does not reimplement the
outbound, inbound, or bidirectional provider algorithms.

## Accepted behavior

- PUSH reads application-owned outbound state, pushes each batch, validates the
  remote acknowledgement, and commits the acknowledgement through storage.
- A PUSH with no pending local changes returns the canonical no-change result
  without calling transport.
- A cache-miss PULL uses the existing checkpoint/apply/checkpoint pipeline.
- A cache-miss BIDIRECTIONAL execution composes the same PUSH and PULL pipelines
  in the configured deterministic order.
- Provider requirements remain exactly `STORAGE + TRANSPORT`; these remote
  plans do not require `CACHE_ACCESS`, queue, scheduler, or connectivity
  provider calls.
- Transport-attempt evidence is truthful when storage fails before transport.
- Classified transport failures preserve their typed remote outcome without
  activating cache fallback, remote-first, refresh, or another strategy.
- If PUSH succeeds and a later PULL fails, `PUSH_REMOTE` remains in
  `completedOperations`; the runtime does not hide or automatically replay the
  completed remote effect.
- Provider-protected execution continues to use the same generic storage and
  transport bridges because the immutable plan requests only those roles.

## Fail-closed boundary

This checkpoint does not implement:

- fresh-hit or stale-hit inline refresh;
- `SERVE_AND_REFRESH` execution;
- durable refresh admission, deduplication, scheduling, retry, or relaunch;
- conflict-safe cache coherence after intervening local changes;
- accepted-plan cache replay;
- strategy-specific durable events and operational read models; or
- complete FULL/DELTA, cancellation, process-death, and platform consumer
  qualification.

Those plans remain rejected before unsupported provider work. The effective
strategy remains `CACHE_FIRST` for every supported remote branch; no hidden
strategy reselection occurs.

## Dependency boundary

This implementation adds no Gradle dependency, repository, plugin, third-party
library, hosted service, database wrapper, networking wrapper, or vendor SDK.
It uses only existing DataLoom contracts, Kotlin, and the shared canonical
pipelines already in the repository.

## Executable evidence

Focused common tests cover:

- PUSH with no local changes and zero transport calls;
- PUSH with one acknowledged local batch;
- classified PUSH transport failure;
- cache-miss BIDIRECTIONAL no-change composition;
- PULL failure after a completed PUSH, including exact completed-operation
  evidence; and
- outbound storage failure before any transport operation.

The permanent shared, Android, and Apple workflows must pass on one immutable
reviewed head before merge. Issues #102 and #101 remain open for refresh,
durable recovery, coherence, events, restart, and complete consumer-path
qualification.
