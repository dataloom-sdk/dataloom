# DL-039B inline cache refresh result contract checkpoint

## Decision

DataLoom now defines a bounded public result contract for a foreground refresh
attempt that occurs after application-owned cache state has already been
verified for local use.

The contract is intentionally separate from
`StrategySynchronizationExecutionResult`. The next runtime slice will compose
local-serving evidence with exactly one `StrategyCacheInlineRefreshResult`
without changing the already frozen cache-only result variants.

## Public outcomes

`StrategyCacheInlineRefreshResult` has four exhaustive terminal outcomes:

| Outcome | Meaning |
|---|---|
| `Completed` | The canonical provider-backed refresh reached `Succeeded` or `Skipped(NO_CHANGES)`. |
| `PartiallySucceeded` | Some refresh work committed but canonical unresolved errors remain visible in the provider-backed partial result. |
| `Failed` | Local cache use remains valid, but the inline refresh failed; transport-attempt, completed-operation, canonical output, and typed remote-outcome evidence remain visible. |
| `Cancelled` | The canonical pipeline returned its explicit cancellation result. |

Every result exposes a stable `StrategyCacheInlineRefreshDisposition`:
`COMPLETED`, `PARTIALLY_SUCCEEDED`, `FAILED`, or `CANCELLED`.

## Canonical evidence invariants

- `completedAt` is derived from the canonical synchronization output. Callers
  cannot supply a second contradictory terminal time.
- `Failed.error` is derived from the canonical failed output. Callers cannot
  pair one error with a different pipeline failure.
- `Completed` accepts only `Succeeded` and `Skipped(NO_CHANGES)`. Constraint,
  policy, and duplicate-request skips cannot be mislabeled as refresh success.
- `PartiallySucceeded` requires canonical partial output so unresolved errors
  cannot be mislabeled as full completion.
- A typed remote outcome requires `transportAttempted=true`.
- Completed `PUSH_REMOTE` or `PULL_REMOTE` operation evidence requires a
  transport attempt.
- `Failed.completedOperations` is defensively copied so already completed
  effects cannot be hidden or mutated after construction.
- `Cancelled` requires canonical cancelled output.

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
- rejection of policy-skip, partial, failed, and cancelled output from
  `Completed`;
- completion time derived from canonical output;
- explicit canonical partial output and bounded partial diagnostics;
- failure error derived from canonical output;
- defensive completed-operation evidence;
- remote-outcome and completed-remote-operation consistency;
- bounded diagnostics that exclude error messages; and
- canonical cancellation enforcement.

The external-consumer fixture compiles all public outcome branches and fields.
JVM and Kotlin/Native ABI baselines must be generated and reviewed on macOS,
then the temporary generation workflow must be removed before the permanent
shared, Android, and Apple matrix runs.

## Remaining integration

This checkpoint does not invoke refresh. The next bounded slice must:

1. add the cache-served-plus-inline-refresh execution result;
2. support only the exact `PULL + SERVE_AND_REFRESH + no durable continuation`
   plan initially;
3. verify cache access before any remote call;
4. reuse the canonical inbound pull pipeline;
5. preserve cache-serving evidence when refresh partially succeeds, fails, or
   cancels;
6. keep BIDIRECTIONAL, durable refresh, deduplication, scheduling, restart,
   coherence, and events fail-closed until separately implemented.

Issues #102 and #101 remain open.
