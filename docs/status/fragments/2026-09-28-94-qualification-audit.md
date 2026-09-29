# Fragment: `#94` (DL-040) qualification audit, re-derived from the current repository

Proposed dashboard changes for the lead to fold into `docs/status/market-readiness.md`.
`docs/status/market-readiness.md` itself was not edited. Full evidence:
[`docs/status/dl-040-qualification-matrix.md`](../dl-040-qualification-matrix.md).

## (a) Proposed "Recently shipped" row

| 2026-09-28 | `#94` docs-only qualification audit (no code, workflow or ABI change). Re-derived from source and from GitHub Actions job logs, not from the row's prose: each of issue `#94`'s eight acceptance criteria crossed with native Android, KMP Android and KMP iOS and with the three durable structures (circuit-breaker state, retry-budget state, retry scheduling/queue). Findings: nothing external blocks the gate (no criterion needs hardware; the KMP Android and Apple Simulator blockers are gone); Android emulator and Apple Simulator prove persisted-state survival across a real process kill for both durable structures, cross-process probe contention on both platforms, and `AC-FUNC-004` through the composed provider flow on native Android and KMP iOS. Still unproven: the composed queue-worker to retry-reschedule to circuit loop over real platform stores; the real circuit gate after a relaunch; `availableAt` after a kill on Android; `FR-RETRY-005` hint parsing beyond the Ktor transport; Android cross-process queue-lease contention; and criterion 8, because fourteen API pages and the `DL-040` reconciliation audit still list proven work as open. The Apple lease contention job is `continue-on-error` and has 36 green / 10 red job conclusions since 2026-09-15 (5 / 3 on the two-Simulator layout, all three reds a slow second-device first launch); the required circuit-breaker contention job had 2 reds in 58. The Android emulator last ran green on `main` at `59ee74f`; `main` at `acc4a02` is red on an unrelated `dataloom-runtime` ABI baseline (PR `#432`). No proof was added, so no percentage moves | `#94` audit |

## (b) Gate row

- **Status:** propose `QUALIFICATION BLOCKED` to `IN PROGRESS`. "Blocked" implies
  something outside the team's control; the audit found none. The dashboard's own
  definition of `IN PROGRESS` ("substantial implementations that still have
  unqualified release behavior") fits exactly. This is a labeling change only and
  is the lead's call; it should not be read as movement toward `COMPLETE`.
- **Est. completion:** **unchanged at 76%.** No code or new proof landed with this
  audit, and nothing it found shows the figure is inflated: of the eight
  criteria, four are met in substance (1, 4, 6, 7), three are partial with a
  concrete residual (2, 3, 5), and one is not met (8). Do not raise the
  percentage until at least backlog items 1 to 3 below have landed.

## (c) "Still pending" text

Row `| 3 |` in the full table currently has no "Still pending" cell (seven
pipe-separated fields where every other row has eight; the pending clause is
appended to the end of "Finished on `main`"). Suggested cell, in order:

1. Reconcile the stale public docs (criterion 8): `docs/audits/DL-040-current-acceptance-reconciliation.md`,
   the three `DL-040-ac-func-004-*-qualification.md` checkpoints, fourteen
   `docs/api/` pages, and the "What remains open" sections of
   `docs/apple/process-termination-proof.md` and `process-contention-proof.md`;
   state which Android topologies are single-process.
2. Composed durable retry loop over real stores on Android and iOS (queue worker,
   failing transport, real evaluator-produced `RetryBudgetState`, `availableAt`
   honored, circuit-open rejection, probe, recovery).
3. Post-relaunch behavior through the real circuit gate in the kill proofs, and
   `availableAt` equality after kill on Android.
4. `FR-RETRY-005` hint normalization in the Retrofit, GraphQL and gRPC transports
   (or a recorded decision to scope it to Ktor).
5. Android cross-process queue-lease contention test or the documented
   single-process statement; make the Apple lease job dependable and drop its
   `continue-on-error`; give the required circuit-breaker contention job the
   two-Simulator layout.
6. Decide what "KMP Android consumer" means (the runtime's `android` variant is
   now what the native Android consumer packages; no KMP-shaped consumer exists).

Remove from the row text: the sentence that the KMP Android provider-flow test
"becomes possible once the roll-out reaches the modules that flow uses". The
roll-out reached `dataloom-core` and `dataloom-runtime` on 2026-09-28 (`#425`), and
the existing instrumented provider-flow test already runs against the `android`
variant.

## Notes for the lead

- Nothing in `#94`'s criteria needs physical hardware. Optional fidelity evidence
  (real iPhone kill, real Android device, real-device iOS contention which needs a
  paid Apple account) is listed in section 5.2 of the matrix.
- The README summary table also carries `QUALIFICATION BLOCKED` for this row
  (`README.md`, gate table); fold the status decision there too.
