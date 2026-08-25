# `RetryPolicy`/`StrategyPolicy` migration onto the policy foundation: investigated, not a bounded slice

[API reference index](./README.md)

## Status

**Investigated (2026-08-25). Not achievable as a bounded, non-breaking
migration — the "Still pending" clause names a category error, not a real
open task.** No code was changed to force this migration; forcing one would
not have produced a genuine architectural improvement, only a lossy,
duplicated encoding of decisions the policy foundation was never shaped to
hold. This document records why, so a future attempt does not re-derive the
same conclusion from scratch.

`docs/status/market-readiness.md`'s `#93` row's percentage is unchanged by
this investigation. Only the "Still pending" wording is corrected to remove
the `RetryPolicy`/`StrategyPolicy` migration clause and replace it with an
accurate, closed characterization.

## What this compares against

[`digest-hmac-secure-random-adoption-investigation.md`](digest-hmac-secure-random-adoption-investigation.md)
closed the other two-thirds of the same "Still pending" clause and explicitly
left "the still-real `RetryPolicy`/`StrategyPolicy`-onto-policy-foundation
migration named separately" as the one remaining, presumed-real item. This
investigation is that follow-up, and it does not confirm the presumption.

`#353` (this session) is the one existing real caller of
`PolicyEvaluator`/`PolicySet`/`PolicyCheckOutcome` — wired as
`DataLoomStrategyAdmissionPolicySpec` into
`StrategySynchronizationExecutionCoordinator`'s admission boundary. Its own
KDoc and the market-readiness changelog entry for `#353` both record that it
*also* considered wiring the same policy foundation into retry/circuit
administration and rejected that path: retry/circuit administration already
has its own dedicated, mandatory `Authorizer` collaborator (see
`RetryAdministrationCoordinatorTest.kt`, `CircuitAdministrationCoordinatorTest.kt`),
and `PolicyDecisionScope` is keyed by `ExecutionId`, which those
administrative commands don't carry. `#353` therefore used
`StrategySynchronizationExecutionCoordinator`'s per-request admission
boundary instead — an *orthogonal, additive* gate in front of strategy
execution, not a replacement of `StrategyPolicy`'s own vocabulary or
decision-making. [`policy-foundation.md`](policy-foundation.md) says this
directly: `StrategyPolicy`'s existing vocabulary is untouched by `#239`/`#353`.

## What "`StrategyPolicy`" and "`RetryPolicy`" actually are

Neither name is a single type. Both are read as shorthand for a package's
whole decision vocabulary:

- **`RetryPolicy`** (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/retry/RetryPolicy.kt`)
  is a synchronous interface: `evaluate(RetryEvaluationRequest): RetryDecision`.
  `RetryDecision` (`RetryDecision.kt`) is a two-way sealed interface —
  `Stop(reason: RetryStopReason)` or `Retry(delay: SchedulingDelay)`. The
  shipped implementation, `StandardRetryPolicy`
  (`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/StandardRetryPolicy.kt`),
  computes `delay` via deterministic fixed/linear/exponential backoff
  arithmetic (`calculateDelay`, `linearDelayMilliseconds`,
  `exponentialDelayMilliseconds`, all overflow-safe) and then optionally
  applies full/equal jitter sampled from an injected, deterministic
  `RetryRandomSource` within a computed bound.
- **`StrategyPolicy`** is not a type name that appears anywhere in source —
  it names `dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/StrategyPolicy.kt`,
  a file of small enums and one evidence data class
  (`StrategyConnectivity`, `StrategyCacheState`, `StrategyProviderHealth`,
  `StrategyRemoteOutcome`, `StrategyConsistency`, `UnknownConnectivityPolicy`,
  `StaleCachePolicy`, `StrategyRuntimeEvidence`) consumed by
  `BuiltInSynchronizationStrategyEvaluator`
  (`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/BuiltInSynchronizationStrategyEvaluator.kt`).
  That evaluator is a large, deterministic `when`-driven procedure covering
  all six V1 strategies (offline-first, remote-first, cache-first,
  network-only, hybrid, adaptive) that produces a full
  `StrategyExecutionPlan`: an ordered list of typed `StrategyOperation`s
  (`READ_LOCAL`, `PUSH_REMOTE`, `ENQUEUE_DURABLE_WORK`, `RECONCILE`, ...), a
  `StrategyDataOrigin`, a `StrategyConsistency` level, required provider
  capabilities, an optional `StrategyFallbackPlan`, and an optional
  `StrategyDurableContinuationPlan`.

## Why "migrating" either onto `PolicySet`/`PolicyCheckOutcome` is a category error

`PolicyCheckOutcome` (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/policy/PolicyCheckOutcome.kt`)
is a four-way graded *admission* decision — `Allow` / `Deny` /
`RequireUserAction` / `Defer(delay)` — each carrying only a required
human-readable `justification` and optional bounded `DataLoomMetadata`. It is
deliberately shaped to answer "may this action proceed," not "what should
happen and in what order."

- **`StrategyPolicy`'s decision is not admission-shaped at all.** A
  `StrategyExecutionPlan` is an ordered sequence of operations plus origin,
  consistency, capability, fallback, and continuation data — there is no way
  to fit "`SERVE_LOCAL` then `RECONCILE`, origin `LOCAL`, consistency
  `EVENTUAL`, with this specific fallback plan" into one of four outcome
  variants without inventing a large, strategy-specific payload on the side —
  at which point the "migration" is not reuse, it is `PolicySet` wearing
  `StrategyExecutionPlan`'s clothes. `PolicyEvaluationInput`'s own KDoc
  independently reaches this conclusion: it explains that
  `StrategyRuntimeEvidence` was deliberately *not* generalized into
  `PolicyEvaluationInput.stateEvidence` because its enums "are shaped around
  synchronization strategy selection specifically... reusing them here would
  relocate the 'subsystem invents its own state model' problem `#93` exists
  to prevent, just in the opposite direction." The same reasoning applies in
  reverse: forcing `StrategyPolicy`'s output through `PolicyCheckOutcome`
  would relocate a strategy-specific execution-planning problem into a
  foundation built for graded admission checks.
- **`RetryPolicy`'s decision is a numeric timing calculation, not a graded
  check.** The `Stop`/`Retry` split is close to a two-way admission decision
  and the protected-category/attempt-limit reasons behind `Stop` are already
  categorical — but the entire value of `Retry` is the computed millisecond
  `delay`, produced by backoff-strategy arithmetic plus bounded jitter
  sampling. `PolicyCheckOutcome.Defer` does carry a `SchedulingDelay`, so in
  principle `Stop`→`Deny`/`Defer` and `Retry`→`Defer(delay)` could be
  encoded — but the backoff/jitter math that produces `delay` would still
  have to live in ordinary Kotlin code outside any `PolicyCheck`, because
  `PolicyCheck.evaluate` only sees `PolicyEvaluationInput` (execution
  context, configuration snapshot, provider health, bounded metadata) and
  has no attempt-number, backoff-strategy, or random-source concept to
  compute a delay from. The "migration" would therefore add a parallel,
  duplicate encoding of a decision `StandardRetryPolicy` already makes,
  contribute no new capability, and still require the same arithmetic to
  live exactly where it lives today.

## Compatibility stakes independently rule out a breaking redesign

Both types are heavily used public API, not peripheral surface:

- `RetryPolicy` flows through `StandardRetryPolicy`, `SynchronizationRetryOrchestrator`,
  `SynchronizationRetryEvaluator`, `RetryEvaluationSupport`,
  `QueueWorkerCoordinator`, `DataLoomQueueSubmission`,
  `QueuedSynchronizationExecutionHandler`, `SynchronizationEventDispatcher`,
  `DataLoomQueueWorkerSpec`, `DataLoomBuilder`,
  `OutboundPushSynchronizationPipeline`, `BidirectionalSynchronizationPipeline`,
  and `SynchronizationConflictOrchestrator` — sixteen `commonMain` files in
  `dataloom-runtime` alone construct or consume it.
- `StrategyPolicy`'s vocabulary (`StrategyRuntimeEvidence` and its enums)
  flows through `StrategyEvaluationRequest`,
  `BuiltInSynchronizationStrategyEvaluator`,
  `StrategySynchronizationExecutionCoordinator`, and every one of the five
  concrete strategy executors (offline-first, remote-first, cache-first,
  network-only, hybrid).

A breaking redesign of either — replacing `RetryDecision`'s shape or
`StrategyExecutionPlan`'s construction path with a `PolicyCheckOutcome`-based
one — would be a major, versioned migration touching both public API
surfaces and every real call site, not a bounded slice. No additive overlay
analogous to `#353`'s `DataLoomStrategyAdmissionPolicySpec` closes this
either: that pattern adds an *orthogonal* pre-check ("may this request be
admitted at all") in front of a coordinator, without touching the
coordinator's own decision vocabulary. `StrategySynchronizationExecutionCoordinator`
already has that orthogonal admission gate as of `#353`. A second, redundant
overlay in front of `BuiltInSynchronizationStrategyEvaluator` or
`StandardRetryPolicy` specifically to satisfy "migration" would not gate
anything `#353`'s admission check and `RetryPolicy`'s own protected-category/
attempt-limit checks don't already gate — it would be architecture for its
own sake, exercising no genuine need.

## Conclusion

This mirrors the pattern already found twice this session (`#357`, `#361`
under `#93`/`#97`/`#98`; also `#95`'s `#362`): a "Still pending" clause named
a plausible-sounding task that, on direct investigation, turns out not to
describe real remaining work. Here, specifically:

- `RetryPolicy` and `StrategyPolicy` make fundamentally different kinds of
  decisions than the policy foundation models — numeric backoff timing and
  ordered execution planning, not graded admit/deny/defer/require-user-action
  checks — so "migrating" either onto `PolicySet`/`PolicyCheckOutcome` would
  be a lossy, duplicative re-encoding, not a real architectural improvement.
- Both types are heavily used, real public API with many production callers;
  a breaking redesign would be a large, separately-scoped, versioned
  migration, not a bounded first slice.
- The one additive-overlay pattern that *does* apply to this area (`#353`'s
  admission gate) has already shipped, is orthogonal to both types' own
  decision-making, and was itself the product of a prior investigation that
  already rejected wiring the policy foundation directly into retry/circuit
  administration for documented, still-valid reasons (a pre-existing
  mandatory `Authorizer` collaborator, and `PolicyDecisionScope`'s
  `ExecutionId` keying not matching administrative commands).

No further code changes follow from this investigation. The corrected
`docs/status/market-readiness.md` wording is the deliverable.

## References

- [Deterministic policy foundation](policy-foundation.md) — `PolicyEvaluator`,
  `PolicySet`, `PolicyCheckOutcome`, and the "deliberately not included"
  section that already states no existing type is migrated onto this
  foundation.
- [Digest/HMAC/secure-random adoption investigation](digest-hmac-secure-random-adoption-investigation.md) —
  the sibling investigation that closed the other two-thirds of the same
  `#93` "Still pending" clause and left this migration named separately.
- [Retry policy](retry-policy.md) — `RetryPolicy`'s own contract
  documentation.
- `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomStrategyAdmissionPolicySpec.kt` —
  `#353`'s real, orthogonal admission-gate precedent, and its KDoc's own
  record of rejecting the retry/circuit administration path.
