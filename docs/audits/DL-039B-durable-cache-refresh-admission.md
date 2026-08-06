# DL-039B durable cache refresh admission checkpoint

## Candidate decision

DataLoom now owns one exact durable cache-first refresh admission slice:

```text
CACHE_FIRST + PULL + SERVE_AND_REFRESH
SERVE_LOCAL → ENQUEUE_DURABLE_WORK → SCHEDULE_REFRESH
STORAGE + CACHE_ACCESS + QUEUE + SCHEDULER
continuation: READ_CHECKPOINT → PULL_REMOTE → PERSIST_REMOTE
```

The caller supplies stable queue and schedule identities through
`StrategyOperationInput.CacheFirstDurableRefresh`. The runtime verifies
application-owned cache state, persists the complete accepted plan through
`QueueIdempotentAdmissionProvider`, and only then invokes `SchedulerProvider`.
The scheduler request is a queue-worker wake-up with `KEEP` semantics and no
application payload.

This checkpoint applies to direct strategy execution. Protected strategy
execution fails closed before provider invocation because queue and scheduler
protection are not yet independently configured by
`DataLoomStrategyProviderProtectionSpec`.

## Side-effect order and outcomes

The exact order is:

```text
cache verification
    ↓
idempotent queue admission
    ↓
scheduler acceptance
```

The payload-free result distinguishes:

- newly or previously accepted `PENDING` work that was scheduled;
- existing `LEASED` or `RETRY_WAITING` work, which is not scheduled again;
- existing terminal work, which is not silently replayed;
- same-ID different-work conflict;
- queue admission failure; and
- scheduler failure after durable queue acceptance.

Scheduler failure never deletes or resets the queue entry. Retrying the same
request reconciles the existing queue identity and may attempt scheduling again.
Provider cancellation continues to propagate normally; if cancellation happens
after queue commit, the durable entry remains available for the same
first-or-existing reconciliation.

## Frozen replay

The admitted `QueueEntry` stores:

- the exact synchronization request;
- the persisted strategy decision;
- the complete original strategy plan; and
- its immutable durable continuation.

Existing accepted-plan queue routing later executes only:

```text
READ_CHECKPOINT → PULL_REMOTE → PERSIST_REMOTE
```

It does not evaluate current cache state, connectivity evidence, profile
configuration, or adaptive policy again. The initial cache verification and
later remote refresh therefore remain separate, auditable truths.

## Fail-closed boundaries

- PULL is the only admitted direction in this slice.
- Non-durable inline refresh remains a separate exact plan.
- The queue provider must implement `QueueIdempotentAdmissionProvider`.
- Missing or generic queue providers are rejected before cache invocation.
- Protected durable refresh is rejected before cache, queue, scheduler, or
  transport invocation until independent queue and scheduler strategy
  protection specifications and adapters exist.
- Queue or schedule identity mismatch becomes a canonical provider failure.
- Queue conflict never invokes the scheduler.
- Existing leased, retry-waiting, or terminal work never creates another queue
  record.
- BIDIRECTIONAL durable refresh, delayed refresh, cancellation administration,
  conflict-safe coherence, and durable refresh events remain outside this slice.

## Dependency and product boundary

No Gradle dependency, repository, plugin, third-party SDK, hosted service,
database wrapper, networking wrapper, analytics integration, or vendor coupling
is added. The runtime uses only existing DataLoom contracts, the idempotent
queue SPI, platform-neutral scheduler SPI, and accepted-plan replay.

Application domain values, repositories, credentials, backend contracts, and
business authorization remain application-owned.

## Executable evidence

The common integration matrix covers:

- cache → queue → scheduler ordering;
- first admission and scheduler acceptance;
- scheduler failure followed by same-identity reconciliation;
- scheduler cancellation after queue admission, with cancellation propagation
  and the durable entry retained;
- queue-provider failure returning `QueueFailed` without scheduler invocation;
- queue-provider identity mismatch returning the canonical identity failure
  without scheduler invocation;
- scheduler receipt identity mismatch returning `ScheduleFailed` while the
  admitted queue entry remains durable;
- one durable entry across caller retries;
- leased and completed duplicate handling without another schedule;
- same-ID different-work conflict;
- rejection of a plain non-idempotent queue provider before cache access;
- protected durable refresh rejection before every provider side effect;
- cache unavailability before queue or scheduler work;
- exact strategy decision and complete plan persistence; and
- frozen accepted-plan replay through the canonical inbound PULL pipeline
  without a second cache-policy evaluation.

Authoritative JVM/Kotlin-Native ABI, shared tests, external-consumer compilation,
Android managed-device/Room validation, and Apple XCFramework/header/Swift
qualification remain required on one immutable reviewed head before merge.

## Remaining V1 work

This checkpoint does not complete cache-first or the six-strategy engine.
Remaining work includes:

1. platform process-termination/relaunch proof for admitted refresh work;
2. scheduler callback and queue-worker reference integration on native Android,
   KMP Android, and KMP iOS;
3. independent queue and scheduler protection for protected durable strategy
   execution;
4. BIDIRECTIONAL refresh and conflict-safe coherence;
5. durable refresh events, read models, cancellation, and administration;
6. online offline-first execution and platform admission implementations;
7. complete hybrid and adaptive runtime matrices; and
8. all release, security, performance, and reference-application gates.

Issues #102 and #101 remain open. The V1 dashboard remains 10% and NO-GO.
