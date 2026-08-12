# Adaptive strategy execution

## Status

**No dedicated executor, by design — verified end-to-end.** Unlike the five
concrete strategies, there is no `AdaptiveStrategyExecutor` class in this
codebase, and none is needed.
`BuiltInSynchronizationStrategyEvaluator.evaluateAdaptive` resolves an
`AdaptiveStrategyProfile` to one of its concrete candidates and delegates
straight to that candidate's own evaluation branch
(`evaluateConcrete`/`evaluateRemoteFirst`/`evaluateHybrid`/etc.) — so the
resulting `StrategyExecutionPlan.effectiveStrategy` is always one of the five
concrete strategies `StrategySynchronizationExecutionCoordinator` already
dispatches (`NETWORK_ONLY`, `REMOTE_FIRST`, `CACHE_FIRST`, `OFFLINE_FIRST`,
`HYBRID`), or the request is `REJECT`ed before reaching any executor at all
(no eligible candidate and no matching `safeDefaultProfileId`).

Investigating this claim before writing any new production code surfaced a
real, already-shipped bug — see "Bug found and fixed" below.

## Selection

`BuiltInSynchronizationStrategyEvaluator.selectAdaptiveCandidate` chooses
deterministically from `AdaptiveStrategyProfile.candidates`, in this fixed
priority order:

1. `hasPendingLocalChanges` → an `OFFLINE_FIRST` candidate, if present.
2. `cacheState == FRESH` → a `CACHE_FIRST` candidate, if present.
3. By `connectivity`:
   - `AVAILABLE` (with transport healthy): `REMOTE_FIRST`, then a `HYBRID`
     candidate with `primarySource = REMOTE`, then `NETWORK_ONLY` — falling
     through to `CACHE_FIRST` then `OFFLINE_FIRST` if none present.
   - `LIMITED`: a `HYBRID` candidate, then `CACHE_FIRST`, then `OFFLINE_FIRST`.
   - `UNAVAILABLE`: `OFFLINE_FIRST`, then `CACHE_FIRST` (only if `STALE`),
     then a `HYBRID` candidate with `primarySource = LOCAL`.
   - `UNKNOWN`/`NOT_EVALUATED`: none of the above — falls through directly
     to the safe default.
4. `AdaptiveStrategyProfile.safeDefaultProfileId`, if none of the above
   matched a present candidate.

If nothing matches, the plan is `REJECT`ed with
`StrategyRejectionReason.NO_ELIGIBLE_ADAPTIVE_PROFILE` before any executor is
invoked. Nested `AdaptiveStrategyProfile` candidates are rejected by
`AdaptiveStrategyProfile`'s own constructor — selection is always exactly one
level deep and bounded by the candidate list size.

This selection logic itself already had dedicated evaluator-level test
coverage (`BuiltInSynchronizationStrategyEvaluatorTest`) before this slice;
what was missing was proof that the *resolved* plan actually executes
correctly once it reaches a real executor.

## Bug found and fixed: `request.profile` unsafe cast

`StrategySynchronizationRequest.profile` is the profile the *caller*
originally submitted, and it is never replaced anywhere after evaluation.
For a plain concrete profile (e.g. `RemoteFirstStrategyProfile`) that field
is already the correct object. But `RemoteFirstStrategyExecutor` and
`HybridStrategyExecutor` both read their own profile-specific fields
(`persistRemoteResult`, etc.) via an unconditional
`request.profile as ConcreteProfileType` cast — and when the caller submitted
an `AdaptiveStrategyProfile` that resolved to `REMOTE_FIRST` or `HYBRID`,
`request.profile` is still the *outer* `AdaptiveStrategyProfile`, not the
resolved candidate. The cast throws `ClassCastException`.

This was a real, already-merged defect in `RemoteFirstStrategyExecutor`
(shipped well before this slice), not something newly introduced —
confirmed by reproducing it directly (`ClassCastException` at the exact cast
line) before writing any fix, per this project's verify-before-fix
discipline. `HybridStrategyExecutor` would have shipped with the identical
gap had this investigation not caught it first.

**Fix**: a new internal helper,
`io.dataloom.runtime.strategy.resolvedProfile(request, evaluation)`, resolves
the actual selected candidate from
`StrategyExecutionPlan.effectiveProfileId` whenever `request.profile` is an
`AdaptiveStrategyProfile`, and returns `request.profile` unchanged otherwise
(zero behavior change for the non-adaptive path). Both executors now call
`resolvedProfile(request, evaluation) as ConcreteProfileType` instead of
casting `request.profile` directly. Purely `internal` — no public API
surface, zero ABI impact.

The other three executors (`NetworkOnlyStrategyExecutor`,
`CacheFirstStrategyExecutor`, `OfflineFirstStrategyExecutor`) never cast
`request.profile` at all — every profile-specific decision for those three
is already baked into `evaluation.plan.operations` by the evaluator, so they
were never at risk. `NetworkOnlyStrategyExecutor` does cast `request.input`
unconditionally, but that is already guarded generically at the coordinator
level (`INCOMPATIBLE_INPUT`, checked against `evaluation.plan.effectiveStrategy`
before any executor runs) for all five strategies, not just network-only —
a different, already-correct mechanism, not a second instance of this bug.

## Verification

`AdaptiveStrategyResolutionTest` (`dataloom-runtime`, 5 tests) proves
adaptive resolution executes correctly end-to-end, directly against the real
evaluator and the real executors (not the full `DataLoomBuilder` facade, the
same lighter-weight pattern the other direct executor test suites use):

- Resolved to `REMOTE_FIRST` and to `HYBRID` — the two previously-broken
  cases. Each configures the resolved candidate with
  `persistRemoteResult = false`, so a passing assertion proves the fix reads
  the *real* candidate's fields, not merely that the cast no longer throws.
- Resolved to `CACHE_FIRST`, `OFFLINE_FIRST`, and `NETWORK_ONLY` — sanity
  checks for candidates that were never at risk, included so adaptive
  execution has coverage across every strategy family, not only the two
  that needed a fix.

Full `dataloom-runtime:jvmTest` and repo-wide `checkKotlinAbi` both verified
green after the fix, confirming zero regressions and zero ABI impact.

## Coordinator wiring

No change to `StrategySynchronizationExecutionCoordinator` was needed or
made. It already dispatches purely on `evaluation.plan.effectiveStrategy`,
which is always one of the five concrete strategies for any adaptive-resolved
`EXECUTE`/`SERVE_AND_REFRESH`-disposition plan.

## Known gap

Every gap already documented for the five concrete executors
(cache-first's and offline-first's and hybrid's shared durable-work
rejection, hybrid's transport-free-PUSH rejection) applies identically when
that strategy is reached via adaptive resolution — adaptive does not widen or
narrow any concrete executor's own scope.
