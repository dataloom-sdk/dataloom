# Fragment: `#93` (DL-039 foundations) Apple file-store adoption by the Room-only durable domains

Proposed dashboard changes for the lead to fold into `docs/status/market-readiness.md`.
`docs/status/market-readiness.md` itself was not edited.

## (a) Proposed "Recently shipped" row

| 2026-09-28 | `#93` (decision D22): Apple production wiring of `AppleFileDurableStateStore` for the five durable domains that were Room-only: `AppleFileDurableDomainStores` (`dataloom-runtime`, `iosMain`) with one factory per domain (`DurablePolicyDecisionLog`, `DurableUnresolvedConflictLog`, `DurableResolvedConflictDecisionLog`, `DurableConflictQuarantineLog`/`DataLoomConflictQuarantineSpec.store`, `DurableAssetTransferSessionStore`), each over the domain's own codec and key encoder reused unchanged (no new schema) in its own explicit file (`dataloom-policy-decision-state-v1.tsv`, `dataloom-unresolved-conflict-state-v1.tsv`, `dataloom-resolved-conflict-decision-state-v1.tsv`, `dataloom-conflict-quarantine-state-v1.tsv`, `dataloom-asset-transfer-session-state-v1.tsv`). New iosTest proofs (`AppleFileDurableDomainStoresTest` over one shared parameterized `AppleFileDurableDomainProof` helper, real files, `runBlocking`): per domain, first write durable and its explicit file present, recovery of the committed record through a fresh store instance, idempotent duplicate absorbed with the record unchanged, and a two-store concurrent duplicate converging on exactly one write plus one absorbed duplicate (the same `Recorded`/`AlreadyRecorded` shape the Apple contention proofs and the Room-backed tests assert); quarantine uses the command-idempotent release (`recordOccurrence` counts, it does not deduplicate) plus a restart-continues-the-count test; asset sessions use duplicate-create rejected as `StaleRevision` plus a chunk-recovery and next-revision-only restart test mirroring the Room-backed one. Verified: `dataloom-runtime` main and test cross-compile for iosArm64, iosSimulatorArm64 and iosX64; ABI baselines regenerated in both configurations (jvm and non-jvm `.api` byte-identical); whole-build `checkKotlinAbi` green in both configurations. NOT verified: execution of any of the new iOS tests (macOS `apple-validation` job only; they were cross-compiled, not run). NOT proven: any of these domains driven through `DataLoomBuilder` on iOS (only strategy diagnostics has that), or a cross-process race for policy decisions, quarantine or asset sessions (the Apple contention and termination proofs cover only the two conflict logs). | `#93` |

## (b) Gate row

`#93` row 0: **unchanged at 87%**. Justification: this closes the named "every other domain remains Room-only in
production/test wiring" gap at the store level for five domains, but the new tests are unrun until macOS CI, no
domain except strategy diagnostics is proven end to end through the builder on iOS, and the other listed pending
items (events/audit adoption, resolved-decision payload persistence, Android runtime parity) are untouched. If the
lead prefers to credit it once the apple-validation job is green, at most +1.

## (c) "Still pending" text

Replace "any other real domain adopting `AppleFileDurableStateStore` (...)" with: policy decisions, both conflict
logs, the quarantine log and asset transfer sessions now have Apple wiring (`AppleFileDurableDomainStores`) and
store-level iOS proofs; still pending, in order: (1) builder-level iOS consumer proofs for the quarantine spec and the
asset-transfer spec (and policy-decision wiring where a builder spec exists); (2) Apple adoption for configuration
history, asset manifest history, strategy decision outcome history and the operational event outbox (still Room-only);
(3) cross-process race proofs for the policy, quarantine and asset-session domains (extend the existing contention
proof modules; deliberately not touched here).

Note: `dataloom-runtime/api/jvm/dataloom-runtime.api` on `main` was stale (missing the `#99` governance surface); the
required regeneration under `DATALOOM_ANDROID_BUILD=true` therefore also brings that baseline back in line.
