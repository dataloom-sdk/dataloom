# Durable queue admission for strategy evaluation

## Status

**Wired, opt-in.** `StrategyDurableQueueAdmitter` bridges an evaluated
strategy plan that requires `ENQUEUE_DURABLE_WORK` into a real,
persisted `QueueEntry`, closing the gap `CacheFirstStrategyExecutor`,
`OfflineFirstStrategyExecutor`, and `HybridStrategyExecutor` previously
handled by rejecting outright
(`StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED`), and
the gap `StrategySynchronizationExecutionCoordinator`'s `DEFER`-disposition
handling previously handled by returning a bare, provider-free
`Deferred` regardless of what the plan actually required.

## Why this was a real gap, not just three rejections

`StrategyQueueAdmissionEvaluator` has existed since `#102`'s first durable
slice — pure, provider-free, and correct — but had zero callers. The actual
enqueue mechanics (`QueuedSynchronizationWorkEncoder` →
`QueueEnqueueRequest` → `QueueProvider.enqueue`) also already existed, but
only as an entirely separate, manually-invoked application capability
(`DataLoom.queueSubmission.submit(...)`). `DataLoom.synchronize()` never
called it automatically. Two consequences followed from that:

- Cache-first's default profile (`requireDurableRefresh = true`) rejected
  the whole call rather than serving cache data and admitting the refresh.
- Offline-first's actual "no connectivity, durably admit the local intent"
  case — the literal scenario its own one-line description promises
  ("commit local intent durably, then synchronize") and the case `#102`'s
  acceptance gate names explicitly ("accepted intent survives process death
  between admission and transport") — was the `DEFER` disposition path,
  which never touched a queue provider at all.

## Opt-in, fully backward compatible

`StrategyDurableQueueAdmitter` takes an optional
`QueuedSynchronizationWorkEncoder`. When `null` (the default — nothing
changes unless an application explicitly configures one), `admit()` returns
`StrategyDurableQueueAdmissionOutcome.NotConfigured` without resolving a
single provider, generating an identifier, or reading the clock. Every
caller that has never configured
`DataLoomBuilder.queueSubmissionEncoder`/`queueSubmissionConfiguration`
observes byte-for-byte the same behavior as before this capability existed:

- Cache-first/offline-first/hybrid's `ENQUEUE_DURABLE_WORK` branches still
  reject with `DURABLE_REFRESH_NOT_YET_SUPPORTED`.
- `StrategySynchronizationExecutionCoordinator`'s `DEFER` disposition still
  returns a bare `Deferred` with `queueEntryId = null`.

Configuring an encoder is the *same* encoder
`DataLoom.queueSubmission` already uses for manual submission — not a
second, parallel configuration surface. Wiring one encoder activates real
durable admission on both the manual path and the automatic
strategy-evaluation path at once.

## What changes once an encoder is configured

### `DEFER` disposition

`StrategySynchronizationExecutionCoordinator` resolves providers (widened
to also cover the plan's durable continuation — see below) and calls
`StrategyDurableQueueAdmitter.admit(...)` whenever the deferred plan
contains `ENQUEUE_DURABLE_WORK`. The contract strengthens from an advisory
"try again later" into a real guarantee:

| Admission outcome | Result |
|---|---|
| Admitted | `Deferred` with a real, non-null `queueEntryId` |
| Rejected (structural) | `Rejected` with the admitter's reason |
| Failed (encoder/provider) | `Failed` with the underlying error |

A caller that opted in by configuring an encoder never silently loses
durability evidence — a failure to actually admit is surfaced as a real
`Rejected`/`Failed`, not folded back into a soft `Deferred`.

### Cache-first / hybrid (`SERVE_LOCAL` also present)

Cache-first's durable-refresh branch and hybrid's `LOCAL`-selected-as-
fallback branch (PULL/BIDIRECTIONAL) always carry `SERVE_LOCAL` alongside
`ENQUEUE_DURABLE_WORK`, and never also carry a remote operation in the same
plan. Admission does not replace serving local state — both happen:
`ServedFromCache.durableQueueEntryId` carries the admitted entry's ID
alongside the actually-served cache state.

### Offline-first / hybrid PUSH (no `SERVE_LOCAL`)

Offline-first's durable branch (`requireDurableQueue = true`, connectivity
available) is different: the evaluated plan carries `ENQUEUE_DURABLE_WORK`
*alongside* the full synchronous remote leg, because connectivity being
available means the evaluator expects both "try now" and "guarantee
durability" from one plan. Running both risks a duplicate remote call once
the durably admitted continuation is later processed by a queue worker —
`QueueProvider.cancel()` exists but cannot reliably prevent an
already-in-flight concurrent worker pickup. Durable admission therefore
*replaces* the synchronous attempt entirely: `OfflineFirstStrategyExecutor`
returns `StrategySynchronizationExecutionResult.DurablyEnqueued` immediately
once admission succeeds, and never runs the pipeline. Hybrid's PUSH-direction
`LOCAL`-as-fallback branch has the identical shape (its operation set is
`[READ_LOCAL, ENQUEUE_DURABLE_WORK, RECONCILE]` — no `SERVE_LOCAL`, nothing
to serve) and is handled the same way.

`RECONCILE`, when present, is never run synchronously in the durable branch
for either strategy — it is owned entirely by whichever coordinator later
replays the durably admitted continuation
(`AcceptedStrategyPlanExecutionCoordinator`), avoiding a double
reconciliation.

## Provider-capability widening

A plan's own `requiredCapabilities` reflect only what runs *synchronously*.
Cache-first's durable-refresh branch, for example, never requires
`TRANSPORT` at the top level — the refresh is meant to run later. But
`StrategyDurableQueueAdmitter` must persist a real
`SynchronizationProviderBindings` (non-null storage and transport provider
IDs) on the queue entry for that later replay to actually resolve
providers. `StrategySynchronizationExecutionCoordinator` widens the
capability set it resolves to the union of the plan's own
`requiredCapabilities` and its `durableContinuation`'s
`requiredCapabilities`, but **only when an encoder is configured** — an
unconfigured caller's resolution requirements are unaffected.

## Identifier and idempotency semantics

`StrategyDurableQueueAdmitter` generates a fresh `QueueEntryId` via
`RuntimeIdentifierGenerators.queueEntryIds` on every admission attempt.
There is no idempotency key derived from the strategy decision — a caller
that retries the same logical `DataLoom.synchronize` call after a `Failed`
outcome may enqueue a duplicate entry. Application-level deduplication is
the caller's responsibility, the same boundary
`DataLoomQueueSubmission` already documents for manual submission.
`availableAt` is always `clock.now()` — durable work admitted this way is
meant to become eligible immediately.

## What consumes the admitted entry

Nothing new. `QueuedSynchronizationExecutionHandler` already routes any
queue entry carrying a `strategyPlan`/`strategyDecision` to
`AcceptedStrategyPlanExecutionCoordinator` for replay — that consumption
path predates this work and required no changes.
