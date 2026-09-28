# Fragment: `#99` (DL-045) governance slice 2: signed policy packs and builder wiring

Proposed dashboard changes for the lead to fold into `docs/status/market-readiness.md`.
`docs/status/market-readiness.md` itself was not edited.

## (a) Proposed "Recently shipped" row

| 2026-09-28 | `#99` slice 2: HMAC-SHA256 signed policy packs (ADR-0005 D10, recorded in ADR-0010) in `dataloom-governance`: `PolicyPackManifest` (names `PolicyCheckId`s in evaluation order, never code; reuses `PolicySetId`, `PolicyCheckId`, `KeyReference`), a versioned, domain-tagged, length-prefixed canonical encoding modelled on the audit chain's, `SignedPolicyPack.sign`, and a non-throwing `PolicyPackVerifier` returning `Valid`/`InvalidSignature`/`UnknownKeyId`/`Malformed`/`UnsupportedVersion` with constant-time comparison through the existing `DataLoomHmacCalculator.verify`. Opt-in `DataLoomBuilder.governanceConfiguration(DataLoomGovernanceSpec)` and a nullable `DataLoom.governance` (independently optional RBAC evaluator, runtime-clock audit log, policy-pack verifier); `dataloom-runtime` now depends on `dataloom-governance`. Verified: governance JVM tests (126 including a modified byte at every offset, wrong key, truncation, empty pack, downgraded and future format version, unknown key id, and two OpenSSL-computed known-answer vectors), the full runtime JVM suite (2026 tests, including 14 new wiring tests), iOS cross-compile main and test on all three targets, ABI baselines regenerated in both configurations. NOT verified: Apple test execution (macOS CI); the module is in no CI job of its own. NOT built: rollback protection (the signed manifest version is not compared with anything), admission of a verified manifest into a `PolicySet`, durable audit persistence, and any runtime enforcement point. | `#99` |

## (b) Gate row

`#99` row 8: **unchanged at 20%**. Justification: signing and wiring extend the foundation, but no FR-ENT
item can be marked complete (FR-ENT-002 lacks rollout, rollback, precedence, admission and any producer of
packs; nothing enforces governance at a runtime boundary), so no honest percentage change is proposed.

## (c) "Still pending" text

Replace "signed policy packs ... builder wiring" with: signed pack verification and opt-in wiring exist;
still pending, in order: durable audit persistence via `DurableStateStore` with retention, overflow and
delivery semantics (FR-ENT-008; `DurableStateStore` has no enumeration, so the audit store layout needs
its own decision) and operational-event bridging; `PolicyCheck` adapter for RBAC through `PolicyEvaluator`
and `DurablePolicyDecisionLog`, pack admission into a `PolicySet`, rollout/rollback and a version floor;
configuration locks (only worthwhile once `LOCAL_OVERRIDE` has a producer); residency; support/fleet
diagnostics; LTS/catalog governance; AC-FUNC-010.

## Notes for the lead

- `dataloom-apple` does not export `dataloom-governance` (same treatment as `dataloom-assets`); Swift
  consumers see `DataLoom.governance` untyped until a macOS-verified follow-up exports it.
- ADR-0007 has no row in `docs/adr/README.md` on main; not reconciled here.
