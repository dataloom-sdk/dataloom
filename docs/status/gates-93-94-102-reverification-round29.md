# `#93` / `#94` / `#102`: round-29 re-verification, not re-derivation

## Status

**Re-verified against current source, 2026-09-11. No change to any of the
three gates' blocked status.** This round's assignment was explicit: these
three gates have each been independently swept for a bounded engineering
slice across at least 4-5 prior rounds, and every time the conclusion was
that everything remaining is genuinely blocked on a `#100` business/
governance decision, missing infrastructure, or an already-closed
investigation. This document re-checks that conclusion directly against
current source rather than trusting the prior prose, and records exactly
what was checked so a future round has a dated reference point instead of
needing to re-derive this from scratch.

No production code, test, or documentation content changed as a result of
this pass beyond this document itself and one market-readiness dashboard
entry (see "What changed on the dashboard" below).

## `#93` (DL-039 foundations, artifacts, compatibility) — 87%, unchanged

Re-checked every item `docs/status/market-readiness.md`'s `#93` row names as
still pending:

- **Artifact graph / BOM / namespace / license / signing** —
  `docs/architecture/artifact-graph-bom-gap-analysis.md` (dated 2026-08-24)
  was re-read in full. Its module-by-module table was cross-checked directly
  against the current `settings.gradle.kts`: the same 26 source modules are
  included today (verified by reading `settings.gradle.kts` end to end,
  counting all `include(...)` blocks — base, Android-gated, and
  Apple-host-gated), and none of the modules the gap analysis says "don't
  exist at all yet" (`dataloom-assets`, `dataloom-jvm`,
  `dataloom-platform-jvm`, `dataloom-bom`) have been added. `ADR-0002`
  (`docs/adr/ADR-0002-v1-artifact-and-foundation-architecture.md`) is
  unchanged since `f51d858` (2026-07-28, last touching commit). `README.md`'s
  license section still reads "to be finalized before V1 publication"
  (`README.md:268`). `#100` (DL-046 immutable V1 release), the issue that
  owns the namespace/license/signing decisions, is still `BLOCKED / NO-GO`
  at 10% with the same three human/business decisions named as its own
  blocker (see its row in `docs/status/market-readiness.md`). Nothing here
  has moved.
- **Retry/strategy-policy migration onto the policy foundation** —
  `docs/api/retry-strategy-policy-migration-investigation.md` (2026-08-25)
  closed this as not achievable: `RetryPolicy` and `StrategyPolicy` make
  decisions structurally incompatible with `PolicyCheckOutcome`'s four-way
  vocabulary. Re-checked directly: `RetryPolicy.kt`
  (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/retry/RetryPolicy.kt`)
  and `StrategyPolicy.kt`
  (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/strategy/StrategyPolicy.kt`)
  have had no commits since 2026-07-29 and 2026-07-28 respectively — the
  structural shape the investigation reasoned about is unchanged.
- **Digest/HMAC/secure-random adoption** —
  `docs/api/digest-hmac-secure-random-adoption-investigation.md` (2026-08-24)
  is unchanged and its remaining named gap (no real call site needing
  `MessageContentRedactor`) is unchanged: a repository-wide search for
  `DataLoomConfigurationResolver` real (non-test) usages still turns up
  exactly the same two production call sites the configuration-resolver
  investigation named —
  `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomStrategyAdmissionPolicySpec.kt`
  and the `dataloom-config`/`dataloom-api` policy plumbing itself — no new
  subsystem has started producing competing configuration layers.
- **KMP Android target** — see `#94` below; identical blocker, unchanged.

Conclusion: `#93`'s 87% and its full "Still pending" text are still exactly
accurate as written. No genuinely bounded slice exists today.

## `#94` (DL-040 retry and circuit breaker) — 72%, unchanged

- **Apple process termination/relaunch** —
  `docs/apple/process-termination-investigation.md` (2026-08-23) was re-read
  in full. Re-ran the search this round's assignment specifically asked for:
  a repository-wide grep for `simctl`/`xcrun`/`xcodebuild test` finds exactly
  three hits, and all three are non-usages — the investigation doc itself
  (prose explaining the absence), `docs/status/market-readiness.md`'s own
  quoted text, and one Kotlin doc-comment in
  `AndroidCircuitBreakerProbeContentionInstrumentedTest.kt` that mentions
  `` `simctl`-style external launcher `` only as a comparison point for why
  the Android proof's simultaneity bound is what it is. Zero actual
  invocations anywhere. `.github/workflows/apple-validation.yml` has not
  been touched since `f51d858`/`f06d4d0` (2026-07-28) — still only
  `./gradlew build`/`assembleDataLoomReleaseXCFramework` and
  `xcodebuild build` (never `xcodebuild test`), confirmed by re-reading the
  workflow file. No new Apple CI infrastructure has appeared. The blocker
  stands exactly as documented: no second, independently-launchable iOS
  process exists, no host-level kill API is callable from in-process test
  code the way `ActivityManager.killBackgroundProcesses` is on Android, and
  building the missing pieces (a real launchable Simulator app target, a new
  `simctl`-based CI script step, a way to read back persisted state from
  outside the app) is infrastructure work, not a bounded PR.
- **KMP Android target** — `docs/android/kmp-android-target-blocker.md`
  (2026-08-14) records the reproduced Gradle plugin-resolution conflict
  against `kotlin = "2.4.10"` / `agp = "9.1.0"`. Re-checked the current
  `gradle/libs.versions.toml` directly: both versions are byte-identical to
  what the blocker doc recorded (`kotlin = "2.4.10"` at line 2, `agp =
  "9.1.0"` at line 5) — no version bump has occurred that might have quietly
  resolved the `com.android.kotlin.multiplatform.library` /
  `com.android.library` classpath conflict. The blocker is unchanged.

Conclusion: `#94`'s 72% and its full "Still pending" text are still exactly
accurate as written.

## `#102` (DL-039B six strategy engine) — 82%, unchanged, one sharper detail

`#102`'s own "Still pending" text says its acceptance criteria are blocked
on `#101` platform parity, not new strategy-engine logic, and that the full
per-profile decision matrix audit was already investigated (2026-09-09) and
found to require the exact same platform-parity proofs `#101`'s own row
tracks branch-by-branch — not a separable audit `#102` could close on its
own.

This round re-verified that conclusion is still current, and it is: `#101`
has shipped two further iOS proofs since that 2026-09-09 investigation
(remote-first's connectivity-`UNKNOWN`/`DEFER` branch, 2026-09-09, and
hybrid's connectivity-`UNKNOWN`/`DEFER` branch, 2026-09-10 — both read
directly from `#101`'s current row and cross-checked against
`BuiltInSynchronizationStrategyEvaluator`/`deriveDurableContinuation` per
this session's standing discipline of never trusting that class's
description without checking it directly). Neither proof changes `#102`'s
own status, and re-reading `#101`'s row in full surfaces a sharper reason
why, worth recording precisely because it was previously only implicit:

**`#102`'s acceptance criteria require native Android, KMP Android, *and*
KMP iOS to expose equivalent observable decisions and recovery guarantees —
three separate platform categories, one of which (KMP Android) has no
target at all today** (`docs/android/kmp-android-target-blocker.md`,
confirmed unchanged above). This means `#102` cannot close even in the
hypothetical where `#101` finishes proving every remaining branch on both
Android and iOS (the explicit-fallback branches requiring
`StrategyLocalFallbackProvider`/`StrategyReconciliationProvider`, physical-
device proof, the Apple background-scheduler tick) — the KMP Android target
gap is not one parity item among several that partial `#101` progress
gradually narrows, it is a standing hard stop on `#102`'s three-platform
requirement independent of how much Android/iOS branch parity work lands.
Prior rounds' framing ("blocked on `#101` platform parity") was accurate but
left it ambiguous whether continued `#101` progress narrows `#102`'s gap
incrementally; this pass confirms it does not, for the KMP Android leg
specifically — that leg only moves when
`docs/android/kmp-android-target-blocker.md`'s Gradle plugin conflict is
resolved, an event with no dependency on `#101`'s Android/iOS branch-proof
work at all.

Conclusion: `#102`'s 82% and its full "Still pending" text remain accurate.
The one new, precise detail is that its KMP Android leg is decoupled from
`#101`'s ongoing Android/iOS parity work — a distinct blocker on the same
critical path, not a shrinking one.

## What would need to change, for each gate

| Gate | What would unblock it |
|---|---|
| `#93` | A `#100` business decision on `io.dataloom` namespace ownership/release authority, final V1 license text, and signing-key custody — none of which is engineering work this session can perform. |
| `#94` | Either a new Kotlin/AGP version pairing that resolves the reproduced `com.android.kotlin.multiplatform.library`/`com.android.library` classpath conflict (see candidate directions in `docs/android/kmp-android-target-blocker.md`), or new host-level Apple CI infrastructure — a real launchable iOS Simulator app target plus a `simctl`-based CI script step plus a way to read persisted state back from outside the app (see `docs/apple/process-termination-investigation.md`). |
| `#102` | Full closure needs both `#101`'s remaining Android/iOS branch-parity work (`StrategyLocalFallbackProvider`/`StrategyReconciliationProvider` real implementations, physical-device proof, Apple background-scheduler tick) **and**, independently, the same Kotlin/AGP version fix that would unblock `#94`'s KMP Android leg — resolving one without the other still leaves `#102` blocked. |

## What changed on the dashboard

`docs/status/market-readiness.md`'s `#93`/`#94`/`#102` rows, percentages, and
"Still pending" text are unchanged — nothing here contradicts or extends
them beyond the one sharper `#102`/KMP-Android decoupling detail captured
above. One "Recently shipped" entry was added pointing here, since that
detail is new and precise enough to be worth a future round finding without
re-deriving it.

## References

- `docs/architecture/artifact-graph-bom-gap-analysis.md` (2026-08-24)
- `docs/api/retry-strategy-policy-migration-investigation.md` (2026-08-25)
- `docs/api/digest-hmac-secure-random-adoption-investigation.md` (2026-08-24)
- `docs/api/configuration-resolver-caller-investigation.md` (2026-08-24)
- `docs/apple/process-termination-investigation.md` (2026-08-23)
- `docs/android/kmp-android-target-blocker.md` (2026-08-14)
- `docs/status/market-readiness.md` — `#93`/`#94`/`#100`/`#101`/`#102` rows
