# Fragment: circuit-breaker contention anomaly follow-up (`#94`, 2026-10-02)

Docs-only, read-only investigation. No production code, test code, or
workflow file changed. Full evidence:
[`docs/apple/circuit-breaker-contention-anomaly-followup.md`](../../apple/circuit-breaker-contention-anomaly-followup.md).

## (a) Proposed "Recently shipped" row

| 2026-10-02 | `#94` read-only follow-up on the required `apple-process-contention-proof` (circuit breaker) job's historical reds, extending `docs/status/dl-040-qualification-matrix.md`'s 2026-09-28 audit with fresh `gh api` evidence through 2026-10-01. Re-derived the job's full observed history (2026-09-15 onward) run by run: 108 non-cancelled job observations, 104 green, 4 red, cleanly resolving into exactly three signatures, none newly discovered -- a now-accepted `CLOCK_REGRESSION` loser reason (2026-09-19), the separate, already-named "result file never appeared within 30s" launch-timing flake (two occurrences, 2026-09-22 and 2026-09-28, the second not previously counted in any prior audit), and the one-time "got 2 ALLOWED" lease-recovery artifact (2026-09-29) that PR `#443` root-caused and fixed the same day (`halfOpenProbeLeaseDuration` widened 5s to 20s). Confirmed from source, not from the fix's own PR description, that production `CircuitBreakerCoordinator`'s generation-uniqueness invariant was never violated (`CircuitBreakerProbeLeaseRecoveryTest`'s `` `expired probe lease is recovered atomically after coordinator recreation` `` independently proves the exact generation-2-after-generation-1 shape observed is intentional recovery behavior) and that `AppleFileCircuitBreakerStateStore`'s `flock`-guarded compare-and-set is a correct cross-process mutual-exclusion primitive. Also found and corrected a factual error in PR `#443`'s own narrative: it claimed the job "failed twice with the identical signature," but direct log inspection of both referenced runs shows only one actually carries that signature; the other is the separate launch-timing flake. Confirmed 34 of 34 consecutive real macOS CI runs green since the fix landed (`2026-09-29T10:13:30Z` to `2026-10-01T16:57:35Z`), with zero recurrence of either failure signature | `#94` anomaly follow-up |

## (b) Gate row

- **Est. completion: unchanged at 79%.** This is an investigation and a
  documentation correction, not new proof, new code, or a newly-closed
  acceptance criterion -- it changes no column of
  `docs/status/dl-040-qualification-matrix.md`'s qualification matrix. It
  does sharpen the risk picture (the correctness-shaped anomaly is now
  confirmed bounded and fixed, with 34 clean runs of fresh evidence since),
  but a single already-landed fix's evidence record catching up to reality
  is not grounds for a percentage bump on its own, and the fragment author
  does not believe it changes the lead's accounting of what remains open
  for `#94` (composed retry/circuit loop over real stores, post-relaunch
  gate re-drive, `FR-RETRY-005` beyond Ktor, Android queue-lease
  contention, criterion 8's stale docs -- none touched here).

## (c) "Still pending" text

Replace the existing clause ("the required circuit-breaker contention job
is 56 green / 2 red across its observed history, a rare and
not-yet-reproduced correctness question tracked separately from the
launch-timing issues") with:

> the required circuit-breaker contention job's full observed history
> (2026-09-15 to 2026-10-01) is 104 green / 4 red across 108 job
> observations, resolving into exactly three known signatures -- a
> now-accepted `CLOCK_REGRESSION` loser reason, the separate, still-
> unmitigated-for-this-job "result file never appeared within 30s"
> launch-timing flake (two occurrences, last 2026-09-28), and a one-time
> "got 2 ALLOWED" lease-recovery artifact (2026-09-29) that PR `#443`
> root-caused against `CircuitBreakerProbeLeaseRecoveryTest`'s own proven
> behavior and fixed by widening the probe lease from 5s to 20s, with 34
> consecutive clean runs since and no further action currently indicated
> (see `docs/apple/circuit-breaker-contention-anomaly-followup.md`)

This is strictly a wording/evidence update to that one clause; every other
"Still pending" item in the `#94` row (composed durable retry loop, post-
relaunch gate re-drive, Android queue-lease contention, criterion 8's stale
docs, etc.) is unchanged and not addressed by this fragment.
