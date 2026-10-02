# Fragment: `#93` (DL-039 foundations) iOS builder-level wiring for the four Apple-file domains

Proposed dashboard changes for the lead to fold into `docs/status/market-readiness.md`.
`docs/status/market-readiness.md` itself was not edited.

## (a) Proposed "Recently shipped" row

| 2026-10-02 | `#93`: iOS builder-level proofs for the four durable domains `AppleFileDurableDomainStores` (`#93`, 2026-09-28) gave real Apple-file production stores to but that, until now, were never driven through `DataLoomBuilder` on iOS -- only strategy-decision diagnostics had that bar. Investigation finding: `DataLoomBuilder`'s own capability specs (`DataLoomStrategyAdmissionPolicySpec.decisionLogStore`, `DataLoomConflictDetectionSpec.unresolvedConflictStore`/`resolvedConflictDecisionStore`/`quarantine`, `DataLoomAssetTransferSpec.sessionStore`) already accept any `DurableStateStore`/`AssetTransferSessionStore` generically -- exactly the same shape `DataLoomStrategyDiagnosticsSpec.store` had when its own iOS gap was closed. No `DataLoomBuilder` or `dataloom-platform-ios` production code needed to change; the gap was purely the missing iOS test proof. Added three new iosTest files in `runtime-ios-reference-consumer` (mirroring `IosReferenceConsumerStrategyDiagnosticsAppleFileTest`'s exact shape -- a real `DataLoomBuilder`, a real operation, a restart-read through a second independently constructed Apple-file store pointed at the same on-disk file): `IosReferenceConsumerPolicyDecisionAppleFileTest` (policy decisions via `strategyAdmissionPolicyConfiguration`, allow and deny cases), `IosReferenceConsumerConflictDomainsAppleFileTest` (both conflict logs and conflict quarantine via one `conflictDetectionConfiguration` capability -- unresolved-conflict recording, resolved-decision recording, and a real two-sync quarantine-at-threshold case), and `IosReferenceConsumerAssetTransferAppleFileTest` (asset transfer sessions via `assetTransferConfiguration`, a real multi-chunk upload followed by a real download against the restart-read session). Verified: all three new files cross-compile cleanly for `iosArm64`, `iosSimulatorArm64`, and `iosX64` (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`, each run individually per this worktree's Gradle-concurrency discipline); zero commonMain/public-API files changed (`git diff origin/main --stat` shows only the three new test files), so no ABI baseline regeneration was needed or performed. NOT verified: execution of any of these three new test files (macOS `apple-validation` job only -- cross-compiled, not run, identical disclosed limitation to every other Apple-side iOS test in this program). NOT proven: the authorized quarantine-release path (`DataLoomConflictAdministrationSpec`) on iOS, or a cross-process race for any of these four domains (the existing Apple contention/termination-proof modules cover only the two conflict logs' store level, not these builder-level paths). | `#93` iOS builder wiring |

## (b) Percentage recommendation: 88% → 89%

Justification: this closes the exact item the row's own "Still pending" cell named --
"builder-level iOS wiring for the four domains `AppleFileDurableDomainStores` ... gave real
Apple-file production stores to but did not wire into `DataLoomBuilder`" -- for all four named
domains (policy decisions, both conflict logs, conflict quarantine, and asset transfer sessions),
with genuine new test proof, not merely a doc rewrite. The bump is kept small (+1, matching this
row's own established discipline) because: (1) the underlying finding is that no production code
needed to change at all -- `DataLoomBuilder`'s specs were already platform-agnostic, so this is
closing a *proof* gap, not adding new runtime capability; (2) none of the three new test files has
executed on real macOS CI yet (cross-compiled only, same disclosed limitation as every other
Apple-side test in this program); (3) the quarantine-release path and cross-process race proofs
for these four domains remain genuinely open.

If the lead prefers to withhold credit until the `apple-validation` job is green on these new
files, 88% unchanged with the rewritten "Still pending" text below is also defensible.

## (c) "Still pending" text

Replace the current cell's clause "builder-level iOS wiring for the four domains
`AppleFileDurableDomainStores` (`#93`, 2026-09-28) gave real Apple-file production stores to but
did not wire into `DataLoomBuilder` — policy decisions, both conflict logs, conflict quarantine,
and asset transfer sessions (strategy-decision diagnostics remains the only domain proven
end-to-end through the builder on iOS)" with:

> All five domains `AppleFileDurableDomainStores` (`#93`, 2026-09-28) gave real Apple-file
> production stores to -- strategy-decision diagnostics, policy decisions, both conflict logs,
> conflict quarantine, and asset transfer sessions -- now have an iOS test proof driving them
> through a real `DataLoomBuilder` (`runtime-ios-reference-consumer`'s
> `IosReferenceConsumerStrategyDiagnosticsAppleFileTest`,
> `IosReferenceConsumerPolicyDecisionAppleFileTest`,
> `IosReferenceConsumerConflictDomainsAppleFileTest`, and
> `IosReferenceConsumerAssetTransferAppleFileTest`). None of these new files has executed on real
> macOS CI yet (cross-compiled only; `apple-validation.yml`'s `macos-15` job is the actual
> pass/fail signal). Still genuinely open: the authorized quarantine-release path
> (`DataLoomConflictAdministrationSpec`) on iOS; cross-process race proofs for the policy,
> quarantine, and asset-session domains on Apple (the existing contention/termination-proof
> modules cover only the two conflict logs' store level); and Apple adoption of
> `DurableConfigurationHistory`, `DurableAssetManifestHistory`, `DurableStrategyDecisionOutcomeHistory`,
> and the operational event outbox (all still Room-only on Apple platforms).

## Verification notes

- Ran from this Windows worktree (no macOS host available). `local.properties` copied in per the
  playbook's own documented workaround (gitignored, not staged).
- `git diff origin/main --stat` before opening the PR shows exactly three new files under
  `runtime-ios-reference-consumer/src/iosTest/kotlin/io/dataloom/consumer/ios/`; nothing in
  `commonMain` or any public API surface changed, confirmed by this same diff and by the fact no
  `*.api`/`*.klib.api` regeneration was necessary.
- `compileTestKotlinIosArm64`, `compileTestKotlinIosSimulatorArm64`, and `compileTestKotlinIosX64`
  for `:runtime-ios-reference-consumer` each ran individually (never concurrently) with
  `-Pdataloom.appleKlibCrossCompile=true -Dorg.gradle.workers.max=2` and all three succeeded.
- Could not verify: actual pass/fail of any new test on a running iOS Simulator (requires macOS
  CI / hardware this Windows host does not have).
