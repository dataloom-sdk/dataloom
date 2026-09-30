# Fragment: `#99` (DL-045) governance slice 3: durable audit persistence

Proposed dashboard changes for the lead to fold into `docs/status/market-readiness.md`.
`docs/status/market-readiness.md` itself was not edited.

## (a) Proposed "Recently shipped" row

| 2026-09-29 | `#99` slice 3: durable audit persistence (ADR-0016, completing what ADR-0010 deferred) in `dataloom-governance`: `DurableAuditStore` implements the existing `AuditStore` port over `DurableStateStore`, keeping the entire hash-chained record list for one host-supplied `AuditStoreScope` as a single `AuditChainState` (no per-record probing for the head), with a versioned `AuditChainStateCodec`. Append is a single compare-and-set against the exact current tail -- a lost race is `HEAD_CONFLICT`, never silently retried, and there is deliberately no idempotent "already appended" outcome for a replayed record (documented rationale in the ADR). No retention/pruning; growth is bounded instead by `AuditChainState.MAX_RECORD_COUNT` (10,000/scope) and the codec's 4 MiB encoded-length limit, matching `OperationalEventOutboxStateCodec`'s own bounds. `DataLoomGovernanceSpec.auditStore` already being a plain `AuditStore` port meant zero changes were needed to `DataLoomBuilder`, `DataLoomGovernanceSpec`, `DefaultDataLoomGovernance`, or `DataLoomGovernance` -- proven by a new end-to-end wiring test rather than a wiring change. Verified: `dataloom-governance` JVM tests (table-driven compare-and-set append covering every head-conflict shape, a genuine concurrent-coroutine multi-appender race proving no lost update/no duplicate chain position, capacity and persistence-failure paths, tamper detection against durably-sourced records reusing the existing `AuditChainVerifier` for mutation/reorder/truncation/wrong-key, decode-time rejection of a truncated or reordered raw payload, restart survival across fresh `DurableAuditStore` instances sharing one backing store, and codec round-trip/malformed-payload tests) all passing; `dataloom-runtime` JVM tests including the new `DataLoomBuilderGovernanceDurableAuditTest`; iOS cross-compile of `dataloom-governance` main and test on the simulator-arm64 target; ABI baselines regenerated and `checkKotlinAbi` passing, diff purely additive. NOT verified: Apple test execution (macOS CI); the module is in no CI job of its own. NOT built: operational-event bridging for audit delivery/export, cross-scope enumeration, key rotation, and everything ADR-0010 already listed as later (`PolicyCheck` adapter, pack admission/rollback, configuration locks, residency, support/fleet diagnostics, LTS/catalog governance, AC-FUNC-010). | `#99` |

## (b) Gate row

`#99` row 8: **unchanged at 20%**. Justification: durable persistence closes a named gap in the audit
foundation but does not complete any FR-ENT item on its own (no runtime enforcement point, no RBAC-to-policy
bridge, no pack admission/rollback), so no honest percentage change is proposed.

## (c) "Still pending" text

Replace "durable audit persistence via `DurableStateStore` with retention, overflow and delivery semantics
(FR-ENT-008; `DurableStateStore` has no enumeration, so the audit store layout needs its own decision) and
operational-event bridging" with: durable audit persistence exists (`DurableAuditStore`, ADR-0016, no
retention by design); still pending: operational-event bridging for audit delivery/export and cross-scope
enumeration. Everything else already listed remains pending unchanged, in order: `PolicyCheck` adapter for
RBAC through `PolicyEvaluator` and `DurablePolicyDecisionLog`, pack admission into a `PolicySet`,
rollout/rollback and a version floor; configuration locks (only worthwhile once `LOCAL_OVERRIDE` has a
producer); residency; support/fleet diagnostics; LTS/catalog governance; AC-FUNC-010.

## Notes for the lead

- `dataloom-governance`'s ABI baselines were regenerated with `-Pdataloom.appleKlibCrossCompile=true`; the
  module has only one ABI layout (no Android target), so the dual-layout `DATALOOM_ANDROID_BUILD=true` dance
  does not apply here.
- No shared hotspot (`DataLoomBuilder`, `settings.gradle.kts`, `dataloom-api` public surface) was touched;
  the only `dataloom-runtime` change is one new, additive test file.
