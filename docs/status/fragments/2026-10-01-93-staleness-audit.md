# Fragment: `#93` (DL-039 foundations, artifacts, compatibility) staleness audit

Read-only audit. No production or test code was changed. Proposed dashboard changes for the
lead to fold into `docs/status/market-readiness.md`; that file itself was not edited.

## Why this audit

`#93` had the least recent fresh attention of the actively-worked gates — its own prior audit
(round 29, commit `63a5cb18`, 2026-09-11) correctly confirmed the row's claims were accurate
*as of that date*. Since then, three weeks of genuine shipped work (2026-09-19 through
2026-10-01) closed or materially changed several of the exact items the row's "Still pending"
cell still names, and at least two already-written status fragments documenting that work
(`docs/status/fragments/2026-09-28-93-apple-durable-domain-adoption.md`,
`docs/status/fragments/2026-09-28-101-kmp-android-rollout-2.md`) were never folded into
`market-readiness.md`. This audit re-verified every factual claim in the current cell directly
against source (not against the prior prose) and found the row's own suspicion — that the KMP
Android clause was stale — confirmed, plus three more stale items the task did not name.

## (a) Proposed "Recently shipped" log row

| 2026-10-01 | `#93` staleness audit (no code change): re-verified every claim in the row's own "Still pending" cell directly against current source. Confirmed **stale** (describes shipped work as still open): (1) the KMP Android target roll-out — `docs/android/kmp-android-target-blocker.md`'s own Status line now reads "Roll-out complete for `#101`'s named list" (`dataloom-model`, `dataloom-provider-api`, `dataloom-plugin-api`, `dataloom-config`, `dataloom-api`, `dataloom-core`, `dataloom-runtime` — seven modules, `androidTargetEnabled` confirmed present in all seven `build.gradle.kts` files), finished by slice 1 (`#412`, 2026-09-20) and slice 2 (`#425`, 2026-09-28), not merely "pilot landed, roll-out mechanical"; (2) the "assets" and "audit" domains named as "remaining out-of-scope domains" for durable-state adoption — `DurableAssetTransferSessionStore` (`dataloom-assets/src/commonMain/.../DurableAssetTransferSessionStore.kt`, `#420`, 2026-09-21) and `DurableAuditStore` (`dataloom-governance/src/commonMain/.../DurableAuditStore.kt`, `#447`, 2026-09-30) both exist, both implement `DurableStateStore`, and both are reachable from real `DataLoomBuilder` call sites (`DataLoomAssetTransferSpec` accepts a `DurableAssetTransferSessionStore`; `DurableAuditStore` plugs into `DataLoomBuilder.governanceConfiguration`'s existing `AuditStore` port, confirmed by `DataLoomBuilderGovernanceDurableAuditTest.kt`); the "events" domain this same clause implies was never actually unaddressed either — `DurableOperationalEventOutbox` (`dataloom-api/.../operational/DurableOperationalEventOutbox.kt`) has been real and `DurableStateStore`-backed since `#320` (2026-08-17, predating the row's own 2026-08-28 writing) and was extended again today by `#458` (2026-10-01, bridging `dataloom-assets`); (3) "every other domain... remains Room-only in production/test wiring" for `DurablePolicyDecisionLog`/both conflict logs — `AppleFileDurableDomainStores` (`dataloom-runtime/src/iosMain/.../AppleFileDurableDomainStores.kt`, commit `3507abf7`, tagged `[#93]`, 2026-09-28) now provides real, cross-compiled Apple-file-backed production wiring for policy decisions, both conflict logs, conflict quarantine, and asset transfer sessions — not Room-only any more at the store level, though (honestly, matching that commit's own fragment) not yet reachable through `DataLoomBuilder` on iOS for any of those four domains, and the new iOS tests are cross-compiled but have not executed on macOS CI; (4) the artifact-graph/BOM gap analysis's module-existence claim is half-stale — `docs/architecture/artifact-graph-bom-gap-analysis.md` (2026-08-24) says "`dataloom-assets`... don't exist at all yet," but `dataloom-assets` was created 2026-09-20 (`#410`) and is now a real, included module (`settings.gradle.kts:68`) with substantial capability; `dataloom-jvm`, `dataloom-platform-jvm`, and `dataloom-bom` genuinely still do not exist, so this item is only partly stale. Confirmed **still accurate** (re-verified, not left unchecked): `DurableConfigurationHistory`'s caller gap (`docs/api/configuration-resolver-caller-investigation.md`'s finding — a fresh `grep -riE "RemoteConfig|FeatureFlag|remoteAssigned|localOverride"` across the whole repo today still returns zero `dataloom-runtime` matches); the `RetryPolicy`/`StrategyPolicy`-to-`PolicyEvaluator` migration ruled not achievable (`RetryPolicy.kt`/`StrategyPolicy.kt` unchanged since 2026-07-28/29, after both the original 2026-08-25 investigation and the 2026-09-11 re-audit); `MessageContentRedactor` still has no real caller outside its own definition file; zero of the twelve ADR-0002 publication coordinates have publishing metadata (`grep` for `maven-publish`/`publishing {` across all `build.gradle.kts` returns nothing); the nine other ADR-0002 source modules (`dataloom-events`, `dataloom-state`, `dataloom-queue`, `dataloom-retry`, `dataloom-policy`, `dataloom-conflict`, `dataloom-storage-spi`, `dataloom-transport-spi`, `dataloom-observability`) remain co-mingled, none exist as separate Gradle modules; `#100` (DL-046) remains `BLOCKED / NO-GO` at 10%, `ADR-0002` line 136-137 still reads "Publication is blocked until namespace ownership and release authority are verified in DL-046," and `README.md:268` still reads "License status: **to be finalized before V1 publication**." Not independently verifiable from this Windows worktree: whether the 2026-09-28 Apple-file-domain iOS tests actually pass on macOS CI (only cross-compilation was ever claimed or checked). | `#93` |

## (b) Percentage recommendation: 87% → 88% (small bump, not unchanged)

Reasoning: four distinct, previously-named blocking items have concrete, already-merged
production code behind them that this row's text still describes as open or mechanical:

- The KMP Android roll-out is **completely finished** for the named module list (not "mechanical
  roll-out remaining") — a clean, unambiguous closure with its own blocker doc confirming
  completion in writing.
- `dataloom-assets` and governance-audit domains both have **real, builder-wired**
  `DurableStateStore` adoptions landed as production code (not test-only), closing two of the
  three domains the cell calls "out-of-scope."
- The Apple-file-domain production code (`AppleFileDurableDomainStores`) is a fourth genuine,
  non-trivial piece of new production capability, even though (per its own honest fragment) it
  stops short of iOS builder-level wiring and macOS CI confirmation.

This is deliberately a *small* bump, consistent with this row's own established discipline of
granting modest credit for genuine new production capability while being explicit about what
remains unproven: no domain besides strategy diagnostics is wired through `DataLoomBuilder` on
iOS yet; the new Apple-file tests have not run on macOS CI; `DurableConfigurationHistory` still
has no real caller; and the governance/business blockers on `#100` (namespace, license, signing)
are completely untouched. The two already-written fragments that independently touched pieces of
this (`2026-09-28-93-apple-durable-domain-adoption.md` suggested "unchanged... at most +1";
`2026-09-28-101-kmp-android-rollout-2.md` said "#93: unchanged") each looked at only one piece in
isolation; taken together with the assets/audit domain adoptions neither fragment considered, the
cumulative, genuinely-new production capability across four independent items supports +1 rather
than 0.

If the lead judges this too generous (e.g. weighting "no iOS builder-level proof yet" and "no
green macOS CI" heavily), 87% unchanged with the rewritten cell below is also a defensible
outcome — the material finding of this audit is the **stale text**, not the percentage.

## (c) Fully rewritten "Still pending" cell

Replace the current cell's text with:

> Wiring `DurableConfigurationHistory` into a real call site — still genuinely blocked, re-verified 2026-10-01 against current source (no subsystem in `dataloom-runtime` produces more than one `ConfigurationSource`-shaped layer for the same setting; `docs/api/configuration-resolver-caller-investigation.md`, 2026-08-24, still holds); resolved `ConflictResolutionDecision` adoption of the durable-state contract (a separate, larger design question given `Merge`'s payload, unchanged); a real call site adopting `MessageContentRedactor` (the primitive exists and is tested, but nothing currently needs it); the `RetryPolicy`/`StrategyPolicy`-onto-policy-foundation migration, investigated and closed as not achievable (`docs/api/retry-strategy-policy-migration-investigation.md`, 2026-08-25, re-confirmed unchanged 2026-09-11 and again 2026-10-01 — `RetryPolicy.kt`/`StrategyPolicy.kt` untouched since July 2026); builder-level iOS wiring for the four domains `AppleFileDurableDomainStores` (`#93`, 2026-09-28) gave real Apple-file production stores to but did not wire into `DataLoomBuilder` — policy decisions, both conflict logs, conflict quarantine, and asset transfer sessions (strategy-decision diagnostics remains the only domain proven end-to-end through the builder on iOS); execution of those new Apple-file-domain tests on real macOS CI (cross-compiled only so far, never run); Apple adoption of `DurableConfigurationHistory`, `DurableAssetManifestHistory`, `DurableStrategyDecisionOutcomeHistory`, and the operational event outbox (all still Room-only on Apple platforms); cross-process race proofs for the policy, quarantine, and asset-session domains on Apple (the existing contention/termination-proof modules cover only the two conflict logs); and the final published artifact graph/BOM — the cited gap analysis (`docs/architecture/artifact-graph-bom-gap-analysis.md`, 2026-08-24) is now stale on one fact (it says `dataloom-assets` "doesn't exist at all yet"; it was created 2026-09-20 and is a real, substantial module today) but otherwise still accurate: `dataloom-jvm`, `dataloom-platform-ios`-sibling `dataloom-ios`, and `dataloom-bom` genuinely do not exist, zero of the twelve ADR-0002 coordinates have any publishing metadata configured in any `build.gradle.kts`, and `dataloom-events`/`dataloom-state`/`dataloom-queue`/`dataloom-retry`/`dataloom-policy`/`dataloom-conflict`/`dataloom-storage-spi`/`dataloom-transport-spi`/`dataloom-observability` remain co-mingled inside `dataloom-api`/`dataloom-runtime` rather than split into their own modules; `ADR-0002` itself still states the `io.dataloom` group is blocked pending `DL-046`'s namespace-ownership/release-authority verification (`#100`, itself still `BLOCKED / NO-GO` at 10%), `README.md`'s license status is still "to be finalized," and no version, target repository, or signing keys are configured anywhere in the build — real business/governance decisions, not engineering work still to do. (Corrected 2026-10-01: the KMP Android target roll-out for `#101`'s named module list — `dataloom-model`, `dataloom-provider-api`, `dataloom-plugin-api`, `dataloom-config`, `dataloom-api`, `dataloom-core`, `dataloom-runtime` — is **complete**, not a remaining mechanical roll-out; the durable-state contract has real, builder-wired production adoptions for the assets domain [`DurableAssetTransferSessionStore`, `#420`] and the governance-audit domain [`DurableAuditStore`, `#447`], and the events domain's `DurableOperationalEventOutbox` has been real and extended since `#320`, 2026-08-17 — none of the three is "out of scope" any longer.)

## Verification notes

- Ran from this Windows worktree; no Gradle build was executed (pure source/doc read, per the
  task's read-only scope). All claims above are grep/`git log`/file-read evidence, cited by exact
  path, line, or commit hash.
- Could not verify: anything requiring a running macOS host or iOS Simulator (the 2026-09-28
  Apple-file-domain tests' actual pass/fail on real hardware or CI). Explicitly listed as
  unverified above rather than assumed.
