# Fragment: `#94` (DL-040) documentation reconciliation (2026-09-29)

Follow-up to the `#94` qualification audit (PR `#438`,
`docs/status/dl-040-qualification-matrix.md`), which found (criterion 8) that
fourteen `docs/api` pages, `docs/audits/DL-040-current-acceptance-reconciliation.md`,
and its three `DL-040-ac-func-004-*-qualification.md` checkpoints still
described proven work as open, and that dashboard row `| 3 |` is missing its
"Still pending" cell (a structural defect, not a content one). This PR
reconciles those documents against the current repository (every statement
changed was re-verified against source, tests, and CI job history, not copied
from the audit). It does not edit `docs/status/market-readiness.md` itself --
that file is the release lead's. This fragment carries the two proposed
changes to row `| 3 |` for the lead to fold in.

## (a) Proposed "Still pending" cell for row `| 3 |`

Row `| 3 |` currently has no separate "Still pending" cell -- its pending
clause is appended to the tail of the "Finished on `main`" cell, one field
short of the header's six columns. Proposed cell, to be split out as its own
field (in order, most-gating first):

1. Public docs describing proven work as open (criterion 8): resolved by this
   PR for the fourteen listed `docs/api` pages, the reconciliation audit, and
   the three AC-FUNC-004 checkpoints. Remove this line once the lead confirms.
2. The composed queue-worker → retry-reschedule → circuit loop has never run
   over a real platform queue store plus a real platform circuit store on any
   platform (fake/in-memory stores only) -- no test exercises
   `CircuitBreakerQueueWorkerCoordinator`/`CircuitBreakerDurableQueueExecutionProcessor`
   end to end against `RoomQueueProvider`+`RoomCircuitBreakerStateStore` or
   `AppleFileQueueProvider`+`AppleFileCircuitBreakerStateStore`.
3. The process-kill/relaunch proofs check that raw persisted state survives;
   none re-drives the real `CircuitBreakerExecutionGate` after the relaunch
   (reject before deadline, one probe at the deadline, recovery), and Android's
   retry-budget kill proof does not assert `availableAt` equality (the Apple
   one covers it only implicitly via a whole-file diff).
4. `FR-RETRY-005` provider/server hint normalization is implemented only in
   the Ktor transport; Retrofit, GraphQL, and gRPC never produce a
   `RetryDelayHint`.
5. No Android test exists for cross-process contention on a queue lease
   (`RETRY_WAITING` entry acquisition); the Apple analog
   (`apple-retry-budget-lease-contention-proof`) is `continue-on-error: true`
   with 36 green / 10 red job conclusions since 2026-09-15 (5 green / 3 red on
   the two-Simulator layout since `#423`; every red a slow, 7-26 minute cold
   second-device app launch, not a correctness failure).
6. What "the mandatory KMP Android consumer path" means is undefined: the
   `android` KMP target now exists on `dataloom-core`/`dataloom-runtime`
   (`#425`, 2026-09-28) and the native Android reference consumer packages that
   variant, but no module has a `commonMain` compiled for an `android` target
   the way the iOS reference consumer does for `iosMain`. This is a
   release-lead decision, not an engineering gap.

Remove from the row's current "Finished on `main`" text: the trailing
sentence "a KMP-Android-specific provider-flow test becomes possible once the
roll-out reaches the modules that flow uses" -- the roll-out reached
`dataloom-core`/`dataloom-runtime` on 2026-09-28 (`#425`), and the existing
`AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest` already
runs against that `android` variant since then (see item 6 above for what
still is not defined).

## (b) Proposed Status/percentage change

- **Status:** propose `QUALIFICATION BLOCKED` → `IN PROGRESS`. Every named
  blocker in the row's own history (Apple process-kill/relaunch, Apple
  cross-process probe contention, the KMP Android target) now has merged,
  CI-exercised evidence (see the six items above for what remains -- all
  engineering/documentation slices, none needing hardware, a paid account, or
  an unsolved toolchain problem). "Blocked" no longer describes this gate;
  the dashboard's own `IN PROGRESS` definition ("substantial implementations
  that still have unqualified release behavior") does. This is a labeling
  change only.
- **Est. completion: unchanged at 76%.** This PR is documentation-only --
  no production code, test, or CI job changed -- so it corrects the record
  without proving anything new. Raise the percentage only when items 2, 3, or
  5 above land with real evidence.

## Evidence

Every citation above was re-verified in this PR against current source,
tests, and `.github/workflows/*.yml` (not copied from the audit). Full detail:
[`docs/audits/DL-040-current-acceptance-reconciliation.md`](../../audits/DL-040-current-acceptance-reconciliation.md)'s
2026-09-28 update, and the per-page updates in the fourteen `docs/api` files
and the three `DL-040-ac-func-004-*-qualification.md` checkpoints.

## Notes for the lead

- `README.md`'s own gate table also carries `QUALIFICATION BLOCKED` for this
  row; fold the status decision there too if adopted.
- Not fixed in this PR (flagged, not resolved): the header KDoc of
  `AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest.kt` still
  says "an explicit KMP-aware Android target is confirmed blocked", and
  `IosReferenceConsumerRetryCircuitQualificationTest.kt`'s matching paragraph
  names a `AndroidReferenceConsumerRetryCircuitQualificationRobolectricTest`
  class that does not exist (the Android test is the instrumented one). Both
  are source-code KDoc, not docs/status or docs/api, so left out of this
  docs-only PR's scope; a follow-up should fix them alongside whichever PR
  next touches those two test files.
