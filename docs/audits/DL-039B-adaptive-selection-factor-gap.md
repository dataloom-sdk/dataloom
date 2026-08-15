# DL-039B adaptive selection-factor gap

## What this checkpoint is

`#102`'s required built-in semantics for adaptive selection read:

> **Adaptive:** bounded deterministic selection among configured concrete
> profiles using operation, freshness, connectivity, provider
> health/circuit state, tenant/workflow configuration, pending local
> state, and the immutable configuration version.

That is seven named selection factors. This checkpoint records which of
the seven `BuiltInSynchronizationStrategyEvaluator.selectAdaptiveCandidate`
actually reads, verified by reading the function body directly rather than
assumed — and which it does not, with exact evidence, so this gap is
tracked precisely instead of being silently covered by tests that only
document current behavior.

## Verified as implemented and read by selection

Confirmed by reading `selectAdaptiveCandidate` (in
`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/BuiltInSynchronizationStrategyEvaluator.kt`)
line by line:

| Factor | Field read | Evidence |
|---|---|---|
| freshness | `evidence.cacheState` | Gates the `FRESH`-hit shortcut and the `UNAVAILABLE`+`STALE` fallthrough |
| connectivity | `evidence.connectivity` | Drives the top-level `when` (`AVAILABLE`/`LIMITED`/`UNAVAILABLE`/`UNKNOWN`/`NOT_EVALUATED`) |
| provider health/circuit state | `evidence.transportHealth` | Gates whether `REMOTE_FIRST`/`HYBRID(remote)`/`NETWORK_ONLY` are even considered under `AVAILABLE` connectivity |
| pending local state | `evidence.hasPendingLocalChanges` | The very first check — routes straight to `OFFLINE_FIRST` if a candidate exists |

## Verified as NOT read by selection

- **`operation`** (`SynchronizationDirection` — PUSH/PULL/BIDIRECTIONAL):
  `selectAdaptiveCandidate(profile, request)` takes `request` but never
  reads `request.direction` anywhere in its body. Direction is used later
  by whichever concrete strategy's own `evaluate*` function runs after
  selection, but it plays no role in *which* strategy adaptive picks.
  Whether that is an intentional simplification (adaptive selects a
  strategy *family*, and per-direction differences are the selected
  strategy's own concern) or a real gap (e.g. `CACHE_FIRST` being
  selected on `cacheState == FRESH` for a PUSH request, where inbound
  cache freshness is arguably irrelevant to an outbound push decision) is
  an open design question, not decided by this checkpoint.
- **`tenant/workflow configuration`**: does not exist anywhere in the
  data model this evaluator can see. Checked both
  `StrategyEvaluationRequest` (`decisionId`, `planId`, `profile`,
  `direction`, `mode`, `evidence` — no tenant/workflow field) and
  `StrategyRuntimeEvidence` (`connectivity`, `cacheState`,
  `storageHealth`, `transportHealth`, `queueHealth`,
  `hasPendingLocalChanges`, `isBackgroundExecutionAvailable` — no
  tenant/workflow field) in
  `dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/`. There
  is no channel for tenant/workflow configuration to reach the evaluator
  at all — this is a real, unimplemented feature, not merely untested.
- **`the immutable configuration version`**: every candidate profile
  carries `configurationVersion: StrategyConfigurationVersion`, but
  `selectAdaptiveCandidate` never reads it as a selection input on any
  candidate.

## Why this is not fixed in this checkpoint

- Adding tenant/workflow-aware selection requires new fields threaded
  through `StrategyRuntimeEvidence`/`StrategyEvaluationRequest` plus real
  design of what tenant/workflow configuration should actually influence
  — genuinely underspecified, multi-PR scope, and risky to guess at
  without product input.
- Wiring `direction` into selection is smaller but is a real behavioral
  change to existing routing (not just new test coverage) for any
  application already running adaptive profiles — needs a deliberate
  decision about the intended semantics, not a unilateral interpretation.
- The remaining decision-matrix dimensions that ARE implemented
  (cache-state, conflict, restart) are independently closeable without
  this design work, so this checkpoint intentionally does not block that.

## Remaining work

- Product/design decision on whether `operation` should influence
  adaptive candidate selection, and if so, exactly how.
- Design and implement a tenant/workflow-configuration channel into
  strategy evaluation, then wire it into `selectAdaptiveCandidate`.
- Decide whether `configurationVersion` should participate in selection
  (e.g. rejecting a candidate whose configuration is stale) or whether its
  only role is to be recorded immutably on the resulting plan (already
  true today, via the selected profile's own `configurationVersion`
  carried into the evaluation result).
