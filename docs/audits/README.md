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
| [DL-AUDIT-004](./DL-AUDIT-004-v1-production-readiness.md) | Current V1 capability and release gates | Primary release-readiness decision record |
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
inspection, API/ABI validation, security review, and the release gates listed
in DL-AUDIT-004.
