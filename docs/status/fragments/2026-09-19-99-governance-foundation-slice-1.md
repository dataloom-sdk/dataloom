# Fragment: `#99` (DL-045) governance foundation, slice 1

Proposed dashboard changes for the lead to fold into `docs/status/market-readiness.md`.
This file is the only status artifact of the PR; the dashboard itself was not edited.

## (a) Proposed "Recently shipped" row

| 2026-09-19 | `#99` slice 1: new `dataloom-governance` module (pure common Kotlin, JVM + iOS targets) with a closed RBAC model and deterministic evaluator answering in `#93`'s `PolicyCheckOutcome` vocabulary (deny-by-default, explicit deny beats allow, multi-role union, no wildcards), a tenant-scoped guard making cross-tenant access a hard denial (no cross-tenant grants in V1), and an append-only HMAC-SHA256 hash-chained audit log with an offline verifier (detects modified, reordered, mid-chain-deleted and first-record-deleted records and wrong keys; detects tail truncation and whole-chain replacement only when an external anchor is supplied, and reports `anchorVerified=false` otherwise). Decisions D7-D10 recorded in ADR-0005 (D10, HMAC-SHA256 signed policy packs with asymmetric signatures deferred, is accepted but NOT implemented). Verified: 86 JVM tests pass against the real `SystemDataLoomHmacCalculator`, including known-answer vectors for the canonical audit encoding whose MACs were computed independently with OpenSSL; iosArm64/iosSimulatorArm64/iosX64 main and test cross-compile; ABI baselines generated and checked. NOT verified: the shared tests have not been run on Apple (needs macOS CI; the module is not in any CI job yet); there is no Android-specific target (the JVM target serves Android, as for sibling modules); nothing is wired into `DataLoomBuilder`; audit storage is in-memory only. | `#99` |

## (b) Gate row

`#99` row 8: **10% -> 20%, status NOT STARTED -> IN PROGRESS** (proposed). Justification: two of the
twelve FR-ENT items (FR-ENT-001 tenant isolation, FR-ENT-004 RBAC) and the tamper-evident half of
FR-ENT-003 now have a real, tested foundation, but none is enforced at any real runtime boundary yet
(no wiring), so no FR-ENT item can be marked complete and AC-FUNC-010 is unchanged. Lead may prefer to
hold at 10% until wiring lands; the slice adds only unwired foundation code.

## (c) "Still pending" text

Replace the row's "investigated and confirmed structurally blocked" parenthetical with: RBAC taxonomy,
tenant isolation, tamper-evident audit and the signature scheme are decided (ADR-0005); RBAC, tenant guard
and audit hash chain exist unwired. Still pending, in order: signed policy packs (D10); `DataLoomBuilder`
wiring (governance behind a `PolicyCheck`, decisions into `DurablePolicyDecisionLog`); durable audit
persistence via `DurableStateStore` and operational-event bridging (FR-ENT-008); configuration locks
(only worthwhile once `LOCAL_OVERRIDE` has a producer); residency; support/fleet diagnostics;
LTS/catalog governance; AC-FUNC-010.

## Index rows the PR deliberately did not edit (shared hotspots)

- `docs/adr/README.md` decision index: add `ADR-0005 | Accepted | Enterprise governance foundation` (and reconcile the
  number: ADR-0003 exists only on the open `#98` PR).
- `docs/architecture/modules.md`: add a `dataloom-governance` row/section (depends on `dataloom-api` and transitively
  `dataloom-model`; nothing depends on it).
- `docs/api/README.md`: link `governance-foundation.md`.
