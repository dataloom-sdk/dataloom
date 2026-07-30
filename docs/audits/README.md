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
| [DL-AUDIT-005](./DL-AUDIT-005-current-v1-conformance.md) | Current V1 conformance after merged retry/circuit slices | Primary current release-readiness and regression decision record |
| [DL-AUDIT-005 foundation addendum](./DL-AUDIT-005-foundation-and-release-addendum.md) | DL-039 foundation and DL-046 release gates | Use with DL-AUDIT-005 for artifact, policy, state, security, platform, and publication prerequisites |
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
gates listed in DL-AUDIT-005 and DL-AUDIT-004.
