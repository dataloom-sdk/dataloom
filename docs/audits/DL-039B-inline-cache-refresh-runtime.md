# DL-039B non-durable inline cache refresh runtime checkpoint

## Candidate decision

The common strategy runtime now composes application-owned cache use with one
foreground PULL refresh for the exact immutable plan:

```text
strategy       CACHE_FIRST
cache state    FRESH or policy-allowed STALE
direction      PULL
disposition    SERVE_AND_REFRESH
operations     SERVE_LOCAL → READ_CHECKPOINT → PULL_REMOTE → PERSIST_REMOTE
capabilities   STORAGE + CACHE_ACCESS + TRANSPORT
origin         LOCAL
continuation   none
```

The runtime verifies cache availability and provider-observed freshness before
any remote operation. Only an available cache proceeds to the canonical inbound
PULL pipeline. The application continues to read its domain value from its own
repository; DataLoom returns freshness, origin, and refresh-operation evidence
only.

## Audit correction

Implementation review found that the canonical inbound pipeline reads the
stored checkpoint before its first remote pull. The original candidate plan did
not declare that read even though execution performed it. The evaluator, exact
runtime guards, queue-safety fixture, execution tests, and documentation now
include `READ_CHECKPOINT` between `SERVE_LOCAL` and `PULL_REMOTE`. This restores
one-to-one correspondence between the immutable accepted plan and every
provider operation performed by the inline refresh path.

## Candidate result

`StrategyCacheServedWithInlineRefreshResult` reports both independent facts:

1. local synchronized state was admitted for use under the immutable cache-first
   plan; and
2. the inline refresh completed, partially succeeded, failed, or returned
   explicit cancellation.

The combined result is public for inspection but runtime-constructed. External
consumers cannot forge unrelated plan and refresh evidence.

Completion time is derived from the canonical refresh result. Local origin and
provider-observed freshness remain visible even when checkpoint read, transport,
apply, or checkpoint persistence later fails.

## Execution semantics

- Cache verification occurs before checkpoint read or transport.
- Provider-reported cache unavailability returns the existing typed
  `CacheUnavailable` result and makes zero refresh calls.
- A fresh admission that becomes stale returns `FRESHNESS_DOWNGRADED` and makes
  zero refresh calls.
- Cache-provider failure remains a normal strategy failure with
  `transportAttempted=false`.
- The canonical inbound pipeline reads the checkpoint, pulls pages, applies each
  inbound change set, and advances a checkpoint only after successful apply.
- Every successful transport page contributes one ordered `PULL_REMOTE` marker.
- A checkpoint failure before the first pull preserves local-cache evidence and
  reports refresh failure with no transport attempt.
- A classified transport failure preserves local-cache evidence and its typed
  remote outcome.
- A local apply or checkpoint-persistence failure after one or more successful
  pulls preserves every completed-pull marker and does not pretend the refresh
  completed.
- Batch-limit partial completion remains distinct from full completion.
- Explicit canonical cancellation remains distinct from failure.
- Adaptive policy may execute this path only when it deterministically selects
  the concrete cache-first profile; the effective strategy remains
  `CACHE_FIRST`.

## Protected execution

The existing plan-aware protection boundary is reused:

- `StrategyCacheAccessProvider` remains independently governed by its cache
  timeout/circuit specification;
- generic storage operations use the existing protected storage bridge; and
- remote pulls use the existing protected transport bridge.

No global state store, inferred scope, or shared vendor policy is introduced.
Provider-protection evidence remains bounded and payload-free.

## Fail-closed boundaries

This checkpoint does not implement or claim:

- durable refresh admission or a refresh work handle;
- queue/scheduler acceptance, deduplication, or single-flight behavior;
- process-death or scheduler-failure recovery;
- BIDIRECTIONAL inline refresh;
- accepted-plan or durable-queue replay of a cache-served refresh;
- conflict-aware cache coherence or invalidation;
- authentication/integrity-specific cache invalidation policy;
- durable cache decision and refresh events/read models; or
- complete native Android, KMP Android, and KMP iOS qualification matrices.

A durable or BIDIRECTIONAL refresh plan is rejected before cache, storage, or
transport invocation. A combined direct result cannot complete a durable queue
entry; the queue mapper fails it closed as an impossible boundary.

## Dependency boundary

This implementation adds no Gradle dependency, repository, plugin, third-party
library, hosted service, database wrapper, networking wrapper, analytics SDK,
or vendor integration. It reuses only DataLoom contracts, Kotlin, already
approved coroutine support, and the canonical shared inbound pipeline.

## Executable evidence

Focused common tests cover:

- immutable-plan declaration of checkpoint read before transport;
- cache verification before checkpoint and transport;
- fresh and policy-allowed stale serving;
- provider unavailability and fresh-to-stale drift;
- cache-provider failure before transport;
- checkpoint failure before transport;
- classified remote failure;
- persistence failure after a successful pull;
- successful multi-page refresh with repeated completed-pull evidence;
- batch-limit partial completion;
- explicit canonical cancellation;
- adaptive selection; and
- fail-closed durable and BIDIRECTIONAL plans.

External-consumer compilation inspects every combined refresh branch without
constructing runtime-owned results or using internal declarations.

Authoritative JVM/Kotlin-Native ABI generation and the permanent shared,
Android, and Apple validation matrix remain mandatory on one immutable reviewed
head before this checkpoint is accepted on `main`.

## Remaining #102 work

1. Add durable refresh admission, identity, deduplication, scheduling, retry,
   circuit, and restart recovery.
2. Add accepted-plan cache refresh replay without current-policy reselection.
3. Add conflict-safe cache persistence, invalidation, and coherence.
4. Add durable cache decision/refresh events, health, operations state, and
   support diagnostics.
5. Complete online offline-first, hybrid, and full strategy failure/restart
   matrices.
6. Qualify the complete native Android, KMP Android, and KMP iOS consumer paths
   under #101.

Issues #102 and #101 remain open. The repository remains NO-GO for V1.
