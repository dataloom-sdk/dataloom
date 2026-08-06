# DL-039B inline cache refresh result contract checkpoint

## Decision

DataLoom now defines a bounded public result contract for a foreground PULL
refresh attempt that occurs after application-owned cache state has already
been verified for local use.

The contract is intentionally separate from
`StrategySynchronizationExecutionResult`. The next runtime slice will compose
local-serving evidence with exactly one `StrategyCacheInlineRefreshResult`
without changing the already frozen cache-only result variants.

## Public outcomes

`StrategyCacheInlineRefreshResult` has four exhaustive terminal outcomes:

| Outcome | Meaning |
|---|---|
| `Completed` | The canonical provider-backed refresh reached `Succeeded` or `Skipped(NO_CHANGES)` after one or more successful remote pulls. |
| `PartiallySucceeded` | Remote work committed and canonical unresolved errors remain visible together with every completed-pull marker. |
| `Failed` | Local cache use remains valid, but the inline refresh failed; transport-attempt, completed-pull, canonical output, and typed remote-outcome evidence remain visible. |
| `Cancelled` | The canonical pipeline returned explicit cancellation while preserving whether transport was attempted and how many pulls completed before cancellation. |

Every result exposes a stable `StrategyCacheInlineRefreshDisposition`:
`COMPLETED`, `PARTIALLY_SUCCEEDED`, `FAILED`, or `CANCELLED`.

## Canonical evidence invariants

- Every canonical synchronization result must have direction `PULL`; PUSH and
  BIDIRECTIONAL results are rejected by this PULL-only contract.
- `completedAt` is derived from the canonical synchronization output. Callers
  cannot supply a second contradictory terminal time.
- `Failed.error` is derived from the canonical failed output. Callers cannot
  pair one error with a different pipeline failure.
- `Completed` accepts only `Succeeded` and `Skipped(NO_CHANGES)`. Constraint,
  policy, and duplicate-request skips cannot be mislabeled as refresh success.
- `Completed` and `PartiallySucceeded` require one or more `PULL_REMOTE`
  markers; a foreground refresh cannot report completion without a completed
  pull.
- Failed and cancelled outcomes may carry zero or more completed
  `PULL_REMOTE` markers. Empty evidence is valid when the first pull fails or
  cancellation happens before a pull completes.
- Repeated `PULL_REMOTE` markers represent successful paged pulls. They are
  bounded by the canonical inbound pipeline's configured maximum batches per
  execution. Push markers, persistence markers, and every other operation are
  rejected.
- `PartiallySucceeded` requires canonical partial output so unresolved errors
  cannot be mislabeled as full completion.
- A typed remote outcome requires `transportAttempted=true`.
- Any completed-pull marker requires `transportAttempted=true`.
- Completed-operation inputs are defensively copied for every outcome that can
  carry partial-effect evidence.
- `Cancelled` requires canonical cancelled output and preserves whether
  transport was attempted before cancellation.

## Safety and payload boundary

- Domain values, cache payloads, credentials, headers, checkpoint contents, and
  arbitrary provider metadata are not part of the contract.
- Diagnostic strings expose bounded status, error count, error code,
  transport-attempt, completed-operation, and typed outcome information without
  rendering error messages or provider payloads.
- Common code uses an exhaustive status mapping rather than runtime reflection.

## Why the contract is separate

A generic `Executed` result would hide whether the application used local cache
state first. A generic `Failed` result would hide that local state had already
been admitted for use. The dedicated refresh outcome allows the next execution
result to report both truths independently:

1. local cache state was available under the admitted freshness policy; and
2. the subsequent inline refresh completed, partially succeeded, failed, or was
   cancelled.

## Dependency boundary

This contract adds no Gradle dependency, repository, plugin, third-party
library, hosted service, database wrapper, networking wrapper, or vendor SDK.
It uses only existing DataLoom models, canonical synchronization results, and
Kotlin collections.

## Executable evidence

Focused common tests cover:

- completed canonical succeeded and no-change output;
- single- and multi-batch completed remote-pull evidence plus defensive copying;
- rejection of completion without a completed pull;
- rejection of non-pull operation evidence;
- rejection of PUSH/BIDIRECTIONAL canonical results;
- rejection of policy-skip, partial, failed, and cancelled output from
  `Completed`;
- completion time derived from canonical output;
- explicit canonical partial output, multi-batch completed-pull evidence,
  defensive copy, and bounded partial diagnostics;
- failure error derived from canonical output;
- first-pull and after-progress failure evidence;
- remote-outcome and completed-pull consistency;
- cancellation before and after completed pulls;
- bounded diagnostics that exclude error messages; and
- canonical cancellation enforcement.

The external-consumer fixture compiles all public outcome branches and fields.
Authoritative JVM and Kotlin/Native ABI baselines are committed. The temporary
write-enabled generation workflow was removed before the final permanent shared,
Android, and Apple validation matrix.

## Remaining integration

This checkpoint does not invoke refresh. The next bounded slice must:

1. add the cache-served-plus-inline-refresh execution result;
2. support only the exact `PULL + SERVE_AND_REFRESH + no durable continuation`
   plan initially;
3. verify cache access before any remote call;
4. reuse the canonical inbound pull pipeline;
5. preserve cache-serving and completed-pull evidence when refresh partially
   succeeds, fails, or cancels;
6. keep BIDIRECTIONAL, durable refresh, deduplication, scheduling, restart,
   coherence, and events fail-closed until separately implemented.

Issues #102 and #101 remain open.
