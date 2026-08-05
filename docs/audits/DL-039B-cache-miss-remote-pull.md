# DL-039B cache-first remote-miss PULL checkpoint

## Decision

The common runtime now executes one additional bounded cache-first plan:

```text
strategy       CACHE_FIRST
cache state    MISSING
connectivity   AVAILABLE
direction      PULL
disposition    EXECUTE
operations     READ_CHECKPOINT → PULL_REMOTE → PERSIST_REMOTE
capabilities   STORAGE + TRANSPORT
origin         REMOTE
```

The plan reuses the canonical inbound pull pipeline. DataLoom reads the stored
checkpoint once, pulls remote changes, applies inbound batches, and advances the
checkpoint only after the associated state has been applied successfully.
Application repositories continue to own domain reads and values.

## Accepted behavior

- A cache-miss PULL does not require or invoke `StrategyCacheAccessProvider`.
- A plain application-owned `StorageProvider` plus `TransportProvider` is
  sufficient for the exact plan.
- A no-change remote response returns the canonical provider-backed skipped
  synchronization result.
- An optional no-change checkpoint is persisted through the normal storage
  contract.
- Checkpoint failure before transport reports `transportAttempted=false`.
- Transport failure reports `transportAttempted=true`, preserves the classified
  remote outcome when available, and performs no cache fallback or strategy
  switch.
- Completed remote operation evidence is retained when a later persistence step
  fails.
- Adaptive selection is supported when it deterministically selects this
  concrete cache-first profile.
- Direct and provider-protected execution use the same immutable plan and
  canonical storage/transport boundaries.

## Fail-closed boundary

This slice does not implement:

- cache-miss PUSH or BIDIRECTIONAL execution;
- fresh-hit or stale-hit refresh;
- inline refresh after serving local state;
- durable refresh admission, deduplication, scheduling, retry, or restart;
- conflict-safe cache coherence after remote persistence;
- accepted-plan cache replay; or
- cache-specific durable events and operational read models.

Those plans remain rejected before unsupported provider work. A cache miss does
not silently become remote-first: the effective strategy remains
`CACHE_FIRST`, the accepted plan remains immutable, and the result exposes the
remote origin selected by that cache-first plan.

## Dependency boundary

This implementation adds no Gradle dependency, repository, plugin, third-party
library, hosted service, database wrapper, networking wrapper, or vendor SDK.
It reuses only existing DataLoom contracts, Kotlin, and the canonical shared
inbound pipeline.

## Executable evidence

Focused common tests cover:

- remote no-change execution without the cache-access extension;
- no-change checkpoint persistence;
- checkpoint failure before transport;
- classified remote failure without persistence or strategy switching;
- adaptive selection of the concrete cache-first miss plan; and
- BIDIRECTIONAL rejection before provider invocation.

The permanent shared, Android, and Apple workflows must pass on one immutable
reviewed head before merge. Issue #102 and platform gate #101 remain open for
the complete cache-first direction, mode, refresh, failure, cancellation,
restart, and consumer-path matrices.
