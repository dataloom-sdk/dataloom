# Audit index

DataLoom audits record evidence at a specific commit or development checkpoint.
Historical audits remain preserved. Use checked-in source and tests first, then
the newest explicitly scoped audit.

> [!CAUTION]
> An older audit can be accurate for its baseline while being wrong for current
> `main`. Do not convert historical findings into present-tense product claims.

| Audit | Scope | Status |
|---|---|---|
| [DL-AUDIT-007 end-to-end V1 release audit](./DL-AUDIT-007-end-to-end-v1-release.md) | Current full audit of gates #93–#102, strategies, platforms, branches, dependencies, security, publication and market evidence | **Authoritative current release decision** |
| [V1 requirements and evidence matrix](./V1-REQUIREMENTS-EVIDENCE-MATRIX.md) | Live Audit-01 mapping for every V1 gate and FR/NFR family | **Active control record** |
| [V1 mainline drift checklist](./V1-MAINLINE-DRIFT-CHECKLIST.md) | Audit-02 source, integration, CI, compatibility, security and documentation checks | **Active control record** |
| [DL-039B durable cache refresh admission](./DL-039B-durable-cache-refresh-admission.md) | Accepted bounded cache-first queue-before-scheduler admission and frozen continuation replay | Partial #102/#101 evidence |
| [DL-039B idempotent queue admission](./DL-039B-idempotent-queue-admission.md) | Atomic first/already/conflict queue identity across in-memory, Room and Apple providers | Partial foundation |
| [DL-039B inline cache refresh runtime](./DL-039B-inline-cache-refresh-runtime.md) | Foreground cache-first PULL refresh composition | Partial #102 evidence |
| [DL-039B cache-first remote direction matrix](./DL-039B-cache-remote-direction-matrix.md) | Direct cache-first PUSH, cache-miss PULL and BIDIRECTIONAL execution | Partial #102 evidence |
| [DL-039B deferred offline-first admission](./DL-039B-offline-first-admission-runtime.md) | Atomic deferred offline-first admission | Partial #102 evidence |
| [DL-040 current acceptance reconciliation](./DL-040-current-acceptance-reconciliation.md) | FR-RETRY-001–012 implementation and remaining real-process/platform blockers | Current retry/circuit scope |
| [DL-AUDIT-006](./DL-AUDIT-006-current-implementation-reconciliation.md) | Earlier strategy/platform/dependency reconciliation | Historical; superseded by DL-AUDIT-007 for current release status |
| [DL-AUDIT-005 foundation addendum](./DL-AUDIT-005-foundation-and-release-addendum.md) | Foundation and release-gate findings | Historical but still useful; its #93 partial finding is reaffirmed |
| [DL-AUDIT-005](./DL-AUDIT-005-current-v1-conformance.md) | Earlier V1 conformance | Historical |
| [DL-AUDIT-004](./DL-AUDIT-004-v1-production-readiness.md) | Expanded V1 baseline and backlog | Historical requirement source |
| [DL-AUDIT-003](./DL-AUDIT-003-full-audit-dl001-dl036.md) | DL-001 through DL-036 | Historical implementation evidence |
| [DL-009–DL-017 recovery audit](./DL-009-DL-017-recovery-audit.md) | Recovery work for early foundations | Historical |
| [DL-010–DL-017 audit](./DL-010-DL-017-audit.md) | Early implementation checkpoint | Historical |

## Evidence hierarchy

When records disagree:

1. checked-in source and tests at the reviewed commit;
2. executable platform and immutable-candidate evidence;
3. accepted ADRs;
4. the newest explicitly scoped audit;
5. older audits and plans.

The current formal V1 score is **0 of 10 accepted gates (0%)**, and the release
verdict is **NO-GO**.
