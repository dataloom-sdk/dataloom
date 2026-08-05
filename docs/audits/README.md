# Audit index

DataLoom audits record evidence at a specific commit or development checkpoint.
They are intentionally preserved as historical records. Use the newest
readiness audit for current release decisions and the source code for current
behavior.

> [!CAUTION]
> An older audit can accurately describe its baseline while being outdated for
> the current branch. Do not turn historical findings into present-tense
> product claims.

| Audit | Scope | How to use it |
|---|---|---|
| [DL-039B cache-miss remote PULL](./DL-039B-cache-miss-remote-pull.md) | Canonical remote PULL and checkpoint persistence after an explicit cache miss | Current bounded cache-miss checkpoint; PUSH/BIDIRECTIONAL, refresh, durable recovery, coherence, events, and complete platform matrices remain under #102/#101 |
| [DL-039B protected cache access](./DL-039B-protected-cache-access.md) | Independently scoped timeout/circuit protection and ordered evidence for direct cache verification | Current protected cache-serving checkpoint; refresh, durable recovery, and platform matrices remain under #102/#101 |
| [DL-039B direct cache serving](./DL-039B-cache-serving-runtime.md) | Provider-observed fresh and allowed-stale cache-first local serving | Direct cache-serving runtime checkpoint; use with the protected-access and remote-miss checkpoints plus #102/#101 |
| [DL-039B cache-access capability](./DL-039B-cache-access-capability.md) | Deterministic cache-serving capability and fail-closed provider resolution | Cache-first planner/resolution checkpoint; use with the serving runtime checkpoints and #102/#101 |
| [DL-039B cache-access contract](./DL-039B-cache-access-contract.md) | Payload-free cache verification and exclusive freshness-deadline evidence | Public cache-first contract checkpoint; use with the capability/runtime checkpoints and #102/#101 |
| [DL-039B deferred offline-first admission](./DL-039B-offline-first-admission-runtime.md) | Atomic provider invocation and durable deferred-admission evidence | Current bounded offline-first checkpoint; use with #102/#101 for its explicit remaining runtime and platform gates |
| [DL-040 current acceptance reconciliation](./DL-040-current-acceptance-reconciliation.md) | Retry/circuit FR-RETRY-001–012 and AC-FUNC-004 reconciliation | Current retry/circuit implementation verdict and remaining process/platform qualification blockers |
| [DL-AUDIT-005](./DL-AUDIT-005-current-v1-conformance.md) | Current V1 conformance after merged retry/circuit slices | Primary current release-readiness and regression decision record outside later scoped reconciliations |
| [DL-AUDIT-005 foundation addendum](./DL-AUDIT-005-foundation-and-release-addendum.md) | DL-039 foundation and DL-046 release gates | Historical foundation/release checkpoint; use newer gate status and source where they supersede it |
| [DL-AUDIT-004](./DL-AUDIT-004-v1-production-readiness.md) | Original expanded-V1 baseline and ordered backlog | Historical baseline; retained for requirement definitions and initial gap evidence |
| [DL-AUDIT-003](./DL-AUDIT-003-full-audit-dl001-dl036.md) | DL-001 through DL-036 | Implementation and verification history |
| [DL-009–DL-017 recovery audit](./DL-009-DL-017-recovery-audit.md) | Recovery work for foundational issues | Detailed corrective evidence |
| [DL-010–DL-017 audit](./DL-010-DL-017-audit.md) | Early implementation checkpoint | Historical gap evidence |

## Evidence hierarchy

When documents disagree, use this order:

1. checked-in source and tests at the reviewed commit;
2. accepted ADRs;
3. the newest explicitly scoped audit;
4. older audits and plans.

Release qualification still requires platform-specific test evidence, artifact
inspection, API/ABI validation, security review, and the immutable-candidate
gates listed in the current README dashboard and DL-046.
