# Circuit-breaker contention proof: anomaly follow-up (`#94`/`#95`, 2026-10-02)

## Status

**Read-only investigation, docs-only.** No production code, test code, or
workflow file touched. This document extends
[`docs/apple/process-contention-proof.md`](process-contention-proof.md) and
[`docs/apple/cross-process-contention-investigation.md`](cross-process-contention-investigation.md)
with fresh CI evidence gathered on 2026-10-02 via `gh api`, and replaces
`docs/status/market-readiness.md`'s `#94` row's stale "56 green / 2 red ...
rare and not-yet-reproduced" "Still pending" text with an evidence-based,
signature-separated count. It does not restate those two documents' own
mechanism/infrastructure findings; read them first.

**Headline finding:** the "56 green / 2 red, rare and not-yet-reproduced"
sentence was accurate when written but is now stale on every count. The
correctness-shaped anomaly it alluded to ("not-yet-reproduced") was in fact
reproduced exactly once (2026-09-29), root-caused as a test-harness timing
artifact, and fixed the same day by PR `#443` (lease widened 5s to 20s,
commit `57eb5c5a`). Thirty-four consecutive real macOS CI runs since that fix
(`2026-09-29T10:13:30Z` through `2026-10-01T16:57:35Z`) show zero
recurrences. Separately, and not previously counted in the dashboard's "56/2"
figure at all, a second occurrence of the **other**, already-named,
unrelated "launch-timing" flake (`result file never appeared within 30s`)
happened one day before the fix landed. The two signatures are confirmed,
by direct log inspection, to be genuinely distinct failure modes, and the
one-time correctness anomaly is confirmed structurally bounded, not a
production defect.

## What this does not re-litigate

- Whether the Simulator-sandbox-bypass mechanism itself is real:
  `docs/apple/cross-process-contention-investigation.md` already confirmed
  this, and it is not re-examined here.
- Whether `AppleFileCircuitBreakerStateStore`'s locking is correct at the
  file level: confirmed below (section "Production code: verified line by
  line") by direct re-reading of the current source, not by trusting the
  prior docs' description of it.
- The "silent first-launched app" / `result file never appeared within 30s`
  flake's root cause: `docs/status/dl-040-qualification-matrix.md` section
  3.4 already named it as a Simulator-launch-latency problem shared with the
  retry-budget lease job, and a two-device mitigation exists for the lease
  job specifically. This document only adds one more dated occurrence of it
  for this job and confirms, by direct log read, that it is not the same
  signature as the "got 2 ALLOWED" anomaly.

## Method

All counts below come from direct GitHub API queries against
`dataloom-sdk/dataloom`, run from this session on 2026-10-02:

1. `gh api repos/dataloom-sdk/dataloom/actions/workflows/apple-validation.yml/runs --paginate` --
   every completed `apple-validation.yml` run (1,048 total, back to
   2026-07-25). Filtered to `status=="completed"`, `conclusion!="cancelled"`,
   `created_at >= "2026-09-15"` (the date `apple-process-contention-proof`
   was added, per `docs/apple/process-contention-proof.md`'s own "Status"
   section) through the newest run this session observed
   (`2026-10-01T16:57:35Z`; three further runs queued/in-flight at session
   time were still `cancelled`, i.e. superseded by a newer push, not yet
   `failure`/`success`, so they carry no evidence either way and are
   excluded).
2. For every run whose top-level `conclusion` was `failure` in that window,
   `gh api repos/dataloom-sdk/dataloom/actions/runs/<id>/jobs` to find
   whether the specific job named
   `"Apple Simulator cross-process probe-contention proof (circuit breaker)"`
   (the `apple-process-contention-proof` job key; this exact display name is
   what distinguishes it from the similarly-named
   `apple-process-termination-proof` ("process-termination/relaunch proof")
   and the conflict-log/retry-budget contention jobs) itself failed, since
   this job carries no `continue-on-error` (confirmed by reading
   `.github/workflows/apple-validation.yml` lines 701-702, 724-725: no
   `continue-on-error:` key between the job header and its first step) --
   a `failure` run conclusion can come from any required job, and a
   `success` run conclusion guarantees every required job, including this
   one, succeeded.
3. For every run in the window after `2026-09-29T10:13:30Z` (the `#443`
   merge timestamp, from `gh pr view 443 --json mergedAt`), the specific
   job's own conclusion was queried directly (not inferred from the run
   conclusion), covering all 34 non-cancelled runs in that range -- see
   the full job-id table in "Evidence since the fix" below.
4. For every job that failed, `gh api repos/dataloom-sdk/dataloom/actions/jobs/<id>/logs --allow-escape-sequences`
   and the exact assertion-failure line(s) were read directly, not inferred
   from the job name or run title.

## Full observed history, by signature (2026-09-15 to 2026-10-01)

| Window | Non-cancelled job observations | Success | Failure |
|---|---:|---:|---:|
| 2026-09-15 to 2026-09-28 (`docs/status/dl-040-qualification-matrix.md` section 3.2's own count, independently spot-checked below) | 58 | 56 | 2 |
| 2026-09-28T12:06Z to 2026-09-29T10:13Z (gap between that audit's capture and the `#443` merge) | 16 | 14 | 2 |
| 2026-09-29T10:13:30Z to 2026-10-01T16:57:35Z (after the `#443` fix) | 34 | 34 | 0 |
| **Total** | **108** | **104** | **4** |

All four failures, by exact signature, read directly from each job's log:

| # | Date (UTC) | Run / job | Branch, head SHA | Signature (verbatim from log) | Category |
|---|---|---|---|---|---|
| 1 | 2026-09-19T06:36:43Z | run `35426328033` / job [`105852811404`](https://github.com/dataloom-sdk/dataloom/actions/runs/35426328033/job/105852811404) | `lead/fix-lease-proof-flake` | `the losing process's rejection reason was 'CLOCK_REGRESSION', expected 'PROBE_IN_FLIGHT'.` App A: `outcome=ALLOWED reason= generation=1`; App B: `outcome=REJECTED reason=CLOCK_REGRESSION generation=-1`. | Accepted-reason gap, already fixed. The assertion script at this commit only accepted `PROBE_IN_FLIGHT`; it was later widened to also accept `CLOCK_REGRESSION` (confirmed present in both later failing jobs' scripts, #3 and #4 below). Under the *current* script this exact outcome would pass. |
| 2 | 2026-09-22T02:40:07Z | run `35679237314` / job [`106592346412`](https://github.com/dataloom-sdk/dataloom/actions/runs/35679237314/job/106592346412) | `main` @ `dcccf28` | `ERROR: one or both result files never appeared within 30s ... result-a.tsv missing`; result-b.tsv: `ALLOWED\t\t1`. | Launch-timing flake ("silent first-launched app"), same category `docs/status/dl-040-qualification-matrix.md` section 3.4 already named for the sibling retry-budget-lease job. Not a correctness signature: App A simply never wrote a result at all. |
| 3 | 2026-09-28T15:22:44Z | run `36421868261` / job [`108926286260`](https://github.com/dataloom-sdk/dataloom/actions/runs/36421868261/job/108926286260) | `93-apple-durable-domain-adoption` | Identical to #2: `ERROR: one or both result files never appeared within 30s ... result-a.tsv missing`; result-b.tsv: `ALLOWED\t\t1`. | Same launch-timing flake as #2, second occurrence. **Not counted in the dashboard's "56 green / 2 red" figure at all** -- it happened after `docs/status/dl-040-qualification-matrix.md`'s own 2026-09-28 capture point (that audit's window ends at `acc4a02`, `2026-09-28T11:58:55Z`; this job started at `12:26:42Z`, ~28 minutes later). |
| 4 | 2026-09-29T02:44:22Z | run `36510332873` / job [`109220801255`](https://github.com/dataloom-sdk/dataloom/actions/runs/36510332873/job/109220801255) | `agent/102-strategy-fixes` | `ERROR: expected exactly one ALLOWED outcome, got 2 (A=ALLOWED, B=ALLOWED).` App A: `outcome=ALLOWED reason= generation=2`; App B: `outcome=ALLOWED reason= generation=1`. | **The correctness-shaped anomaly** the dashboard's "56/2" sentence referred to as "rare and not-yet-reproduced." This is the one and only occurrence of this specific signature anywhere in the job's observed history (2026-09-15 to 2026-10-01). Diagnosed and fixed same day by PR `#443` / commit `57eb5c5a`. |

Independent spot-check of row 1's source matrix: `docs/status/dl-040-qualification-matrix.md`
line 120 (`apple-process-contention-proof (circuit-breaker probe) | 58 | 56 | 2 | no`)
and lines 163-166 name exactly these same two failures (`CLOCK_REGRESSION`,
job `105852811404`; the silent-app symptom, job `106592346412`) for its own
58-run window -- the counts above are consistent with, and additive to, that
prior audit, not a re-derivation that contradicts it.

## A correction to PR `#443`'s own stated evidence

PR `#443`'s body (`gh pr view 443 --repo dataloom-sdk/dataloom`) states:

> The required `apple-process-contention-proof` job (circuit breaker) has
> now failed twice with the identical signature: both processes report
> `ALLOWED`, App A always at `generation=2`, App B always at `generation=1`.

Having now read every failing job's log directly rather than trusting that
summary, **only one run shows this signature**: row 4 above
(run `36510332873`, job `109220801255`, 2026-09-29T02:44:22Z). The other
run-level failure closest to it in time, row 3 above (run `36421868261`, job
`108926286260`, 2026-09-28T15:22:44Z), is the *separate* "result file never
appeared within 30s" signature -- App A's result file is missing entirely,
not present with `outcome=ALLOWED`. These are not the same failure mode:
one is two processes both legitimately completing `acquire()` and both
being granted a probe; the other is one process never completing at all.

This does not weaken the production-code diagnosis itself -- that diagnosis
is independently verified against the coordinator's own source and its own
unit test in the next section, and holds regardless of how many times the
specific signature was observed. It does mean the historical count PR `#443`
narrated ("failed twice ... identical signature") does not match what the
CI logs actually show; the accurate statement is "failed once with this
signature, and once with a different, already-known, unrelated signature,
in the two days before the fix landed." Flagging this precisely, since the
task asked for exact, non-conflated signatures rather than trusting a prior
summary.

## Production code: verified line by line

### `AppleFileCircuitBreakerStateStore` (`dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/retry/AppleFileCircuitBreakerStateStore.kt`)

- `compareAndSet` (lines 99-130) runs entirely inside
  `withExclusiveLock` (line 106, delegating to
  `AppleCircuitStateFileBoundary.withExclusiveLock`, lines 171-185): it opens
  (or creates) a dedicated `.lock` file and calls POSIX `flock(descriptor,
  LOCK_EX or LOCK_NB)` in a retry loop (`acquireExclusiveLock`, lines
  200-210), treating `EAGAIN`/`EWOULDBLOCK` as "retry after 5ms" and any
  other `errno` as a thrown `AppleCircuitFileException`. `flock` is a
  kernel-level, process-shared primitive (confirmed by the Apple Simulator
  investigation doc's own reasoning, which this store's own KDoc at lines
  69-74 restates: "Compare-and-set therefore remains exact across multiple
  store instances and cooperating app processes that use the same directory
  and file name").
- Inside the lock, `compareAndSet` reads the current on-disk snapshot
  (`boundary.readSnapshot()`, line 107), checks the caller's
  `expectedVersion` against the current record's `version` (lines 111-114),
  and only on a match writes a new record with `version + 1`
  (lines 118-123) via an atomic write-temp-then-`rename` (lines 192-198,
  517-566: `fsync` the temp file, `close`, `rename(2)` over the destination,
  then `fsync` the parent directory). A mismatch returns `Conflict` (line
  116) without writing anything.
- This is a textbook correct optimistic-concurrency CAS under a real
  cross-process mutual-exclusion lock: at no point can two processes'
  `compareAndSet` calls interleave their read-check-write sequence, because
  the entire sequence happens while each holds the same `flock`.

### `CircuitBreakerCoordinator` (`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/CircuitBreakerCoordinator.kt`)

- `acquire()` (lines 29-66) captures `observedAt = clock.now()` **once**,
  before its retry loop (line 32), then repeatedly `load()`s the current
  record, calls `evaluateAccess` (a pure function of `current` and
  `observedAt`), and on `AccessTransition.StartProbe` attempts
  `compareAndSet` against the store -- retrying only on `Conflict` (line 54),
  up to `configuration.maximumStateUpdateAttempts` times.
- `evaluateAccess` (lines 169-204): in `HALF_OPEN` phase, compares
  `observedAt` against the persisted `probeLeaseUntil` (line 182-183). If
  the lease has **not** expired, it rejects with `PROBE_IN_FLIGHT` (line
  185). If it **has** expired, it calls `startProbe` (line 189) -- which
  issues a **new** probe at `current.probeGeneration + 1` (line 225) with a
  fresh lease (lines 216-219, 226, 237). This is the exact mechanism
  PR `#443` described: a caller that observes an already-expired lease is
  not treated as racing the prior holder at all -- it is treated as a
  legitimate new probe attempt, and is granted one.
- This behavior is deliberate and independently proven correct by
  `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/retry/CircuitBreakerProbeLeaseRecoveryTest.kt`,
  specifically the test named
  `` `expired probe lease is recovered atomically after coordinator recreation` ``
  (lines 50-58 of that file), which asserts the recovered probe's
  `generation` is `2L` after an unresolved `generation=1` probe's lease
  expires -- **exactly** the `A=generation 2, B=generation 1` shape observed
  in the one real "got 2 ALLOWED" CI failure (row 4 above). The mutual-
  exclusion invariant this coordinator actually promises -- "no two callers
  ever hold the *same* generation's permit at the same time" -- was never
  violated in that failure: App A was never granted generation 1 (App B's
  own permit); it was granted a distinct, later generation 2, because by the
  time its own `acquire()` call ran, the system had legitimately moved on
  from the state App B's permit was issued against.

### Why this is a harness artifact, not a production defect

The proof harness's own assertion (`apple-validation.yml`, read directly,
e.g. around line 1559-1561 in the current file) is "exactly one `ALLOWED`" --
an assertion about the proof's *two racing processes*, not about the
coordinator's own generation-uniqueness invariant. The coordinator was
never asked to guarantee "at most one process is ever granted *any* probe
across the lease's full lifetime regardless of real-world delay before that
process's own `acquire()` call runs" -- no such guarantee is possible for
any lease-based design, Apple's or anyone else's, once a caller's own call
can be arbitrarily delayed by the OS before it even begins. The harness's
assertion implicitly assumed both processes would call `acquire()` within
a bounded window of each other; iOS Simulator app-backgrounding/CPU-
throttling of the first-launched process (`AppDelegate.swift:94-100`
already documents this risk, per PR `#443`'s own citation) breaks that
assumption on rare occasions, not the coordinator's own correctness.

## Evidence since the fix

Every non-cancelled `apple-validation.yml` run from the `#443` merge
(`57eb5c5a`, `2026-09-29T10:13:30Z`) through the newest run read by this
session (`2026-10-01T16:57:35Z`) -- 34 runs, each queried directly by job ID,
not inferred:

| Run | Job ID | Conclusion | Head SHA / branch |
|---|---|---|---|
| 36555172042 | 109362475422 | success | `agent/102-strategy-fixes` |
| 36559948224 | 109379039541 | success | `main` @ `c9d870a` |
| 36563220902 | 109388833253 | success | `102-conflict-through-strategy-facade-and-doc-refresh` |
| 36563490537 | 109389719928 | success | `feature/99-durable-audit-persistence` (run-level `failure` from the unrelated `apple-validate` job only -- confirmed by job breakdown) |
| 36563518728 | 109389811196 | success | `feature/97-file-backed-asset-storage` |
| 36563889647 | 109391024739 | success | `agent-94-composed-queue-circuit-retry-budget-gate` |
| 36607158144 | 109539435801 | success | `main` @ `9a4e5c5` |
| 36670629961 | 109744660516 | success | `feature/99-durable-audit-persistence` |
| 36671401093 | 109746973170 | success | `docs/dashboard-sync-2026-09-29-round3` |
| 36672373382 | 109749936287 | success | `feature/102-conflict-facade-offline-hybrid` |
| 36672782849 | 109751195124 | success | `feature/94-circuit-gate-redrive-after-relaunch` |
| 36672844008 | 110180972659 | success | `docs/96-qualification-audit` |
| 36673296227 | 109752761527 | success | `feature/97-apple-file-backed-asset-storage` |
| 36673423062 | 109753142844 | success | `main` @ `759686c` |
| 36804413764 | 110186166193 | success | `main` @ `f8b2e37` |
| 36804769704 | 110186545065 | success | `docs/dashboard-sync-2026-10-01-round4` |
| 36805719882 | 110211991104 | success | `feature/101-cache-first-pull-queue-entry-id` |
| 36806486495 | 110191862909 | success | `feature/98-plugin-certification-kit` |
| 36807222035 | 110194157553 | success | `feature/96-assets-outbox-bridge` |
| 36808919363 | 110199371443 | success | `docs/94-95-emulator-real-execution-evidence` |
| 36815471731 | 110219712273 | success | `feature/97-parallel-asset-transfer` |
| 36816230451 | 110222944676 | success | `main` @ `c7c870a` |
| 36841799144 | 110302397274 | success | `main` @ `2183efb` |
| 36842511394 | 110321279213 | success | `docs/dashboard-sync-2026-10-01-round5` |
| 36844455909 | 110311133132 | success | `docs/93-staleness-audit` |
| 36845901580 | 110315846055 | success | `feature/97-content-policy-hooks` |
| 36845964231 | 110474749228 | success | `feature/96-deployable-dashboard-adaptor` |
| 36846027344 | 110474760867 | success | `feature/98-reference-plugin` |
| 36851456714 | 110333829747 | success | `docs/97-apple-aes-gcm-investigation` |
| 36860847028 | 110365575378 | success | `main` @ `d7988a4` |
| 36894259102 | 110477163944 | success | `docs/dashboard-sync-2026-10-01-round6` |
| 36895332123 | 110481963545 | success | `main` @ `4989475` |
| 36895407952 | 110481017902 | success | `feature/97-transport-backed-provider` |
| 36895832534 | 110683441770 | success | `feature/97-content-policy-reference-impl` |

**34 of 34 green.** No recurrence of the "got 2 ALLOWED" signature, and no
recurrence of the "result file never appeared" signature either, in this
window.

## Verdict

Of the three possibilities named in this follow-up's charge:

- **(a) genuine, rare production correctness defect** -- ruled out by direct
  source reading. `CircuitBreakerCoordinator`'s generation-uniqueness
  invariant was never violated; `CircuitBreakerProbeLeaseRecoveryTest`
  independently proves the exact generation-2-after-unresolved-generation-1
  shape observed is intentional, tested recovery behavior, not a bug.
- **(b) test-harness/CI-timing artifact** -- confirmed, for the one real
  occurrence of this specific signature (row 4). The harness's "exactly one
  `ALLOWED`" assertion encodes an assumption (both racers call `acquire()`
  within a bounded window of each other) that iOS Simulator app-
  backgrounding can occasionally violate; PR `#443` narrowed, but by its own
  documented admission did not structurally eliminate, this risk by widening
  the lease from 5s to 20s.
- **(c) a new, distinct failure mode** -- not found. Every failure in the
  job's full observed history resolves to one of exactly three known
  signatures: the now-accepted `CLOCK_REGRESSION` loser reason, the separate
  "result file never appeared" launch-timing flake (two occurrences, last
  seen 2026-09-28, still unmitigated for this specific job -- it has no
  two-device mitigation the way the sibling retry-budget-lease job does,
  per `docs/status/dl-040-qualification-matrix.md` section 3.4), and the
  one-time "got 2 ALLOWED" lease-recovery artifact PR `#443` fixed.

**Is the 20s lease adequate?** The evidence available -- 34 consecutive
real runs with zero recurrence -- is consistent with "yes, for CI as
observed so far," but is not, and cannot by its nature be, a proof of
sufficiency: the module's own KDoc (lines 235-237) already states this
correctly -- "this narrows, but does not structurally eliminate, the race:
iOS background-throttling delay is not formally bounded." Thirty-four clean
runs is a modest sample for a failure that, even before the fix, occurred
only once in 108 observations (~0.9%). This document does not recommend
widening the lease further: there is no fresh evidence of a recurrence to
act on, and a larger margin than 20s (already 4x the original 5s, and
already comfortably inside the CI script's 30s result-file wait budget)
would need to trade against that same 30s budget. If this signature recurs
again, the next bounded step named by the module's own KDoc and by this
document is either widening the 30s result-file wait budget (to allow a
still-larger lease) or applying the two-device mitigation
`docs/status/dl-040-qualification-matrix.md` section 3.4 already describes
for the sibling lease job -- not re-diagnosing from scratch.

## References

- [`docs/apple/process-contention-proof.md`](process-contention-proof.md) --
  the original infrastructure document; unmodified by this follow-up.
- [`docs/apple/cross-process-contention-investigation.md`](cross-process-contention-investigation.md) --
  the Simulator-sandbox-bypass mechanism investigation; unmodified.
- [`docs/status/dl-040-qualification-matrix.md`](../status/dl-040-qualification-matrix.md) --
  section 3.2/3.4, the prior 58-run audit this document's own count extends.
- PR `#443` (`lead/circuit-breaker-proof-lease-margin`, commit `57eb5c5a`,
  merged `2026-09-29T10:13:30Z`) -- the fix this document evaluates fresh
  evidence against.
- `dataloom-runtime/src/iosMain/kotlin/io/dataloom/runtime/retry/AppleFileCircuitBreakerStateStore.kt`,
  `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/retry/CircuitBreakerCoordinator.kt`,
  `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/retry/CircuitBreakerProbeLeaseRecoveryTest.kt`,
  `apple-process-contention-proof/src/iosMain/kotlin/io/dataloom/processcontentionproof/AppleCircuitBreakerProbeContentionProof.kt` --
  all read directly for this document.
- `.github/workflows/apple-validation.yml` -- the `apple-process-contention-proof`
  job, read directly for its exact assertion text and device-count
  (unchanged: still one Simulator device for this job).
