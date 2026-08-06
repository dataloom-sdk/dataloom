# V1 parallel AI-agent operating model

Status: **active for full V1 execution**  
Owner: DataLoom product owner and maintainers  
Applies to: issues `#94` through `#102`, release gate `#100`, and all supporting pull requests

## Purpose

DataLoom V1 has a large, interdependent scope. Multiple AI-assisted engineering
sessions may work in parallel, but integration must remain deterministic,
reviewable, and independently audited.

The operating principle is:

> **Parallel development, serialized and independently audited integration.**

This model accelerates implementation without allowing agents to modify the
same source boundaries concurrently, use GitHub Actions as an interactive
debugger, weaken release criteria, or merge incomplete behavior into `main`.

An "agent" in this document means a separate GitHub-connected engineering
session with its own branch, worktree or isolated checkout, issue scope, and
handoff. A single chat session is not treated as an autonomous background
worker.

## Product invariants

Every agent must preserve these decisions:

- DataLoom is a provider-neutral Kotlin Multiplatform synchronization SDK.
- Offline-first, remote-first, cache-first, network-only, hybrid, and adaptive
  are all mandatory built-in V1 strategies.
- Strategy, direction, transfer mode, and trigger remain independent.
- Native Android, KMP Android, and KMP iOS are mandatory V1 consumer paths.
- Applications own domain values, repositories, backend contracts,
  authentication, credentials, authorization, and presentation state.
- DataLoom owns deterministic admission, orchestration, durable recovery,
  retry/circuit behavior, conflict coordination, and bounded operational
  evidence.
- No new third-party dependency, SDK, hosted service, database wrapper,
  networking wrapper, analytics integration, or vendor coupling may be added
  without explicit product-owner and architecture approval.
- No release gate or completion percentage increases until every acceptance
  criterion for that gate has executable evidence on one reviewed commit.

## Team topology

Start with twelve active roles: `Lead-01`, `Lead-02`, `Audit-01`, `Audit-02`,
and `Build-01` through `Build-08`. Add `Build-09`, `Build-10`, and
`Release-Audit` when their dependencies and release-evidence work are ready.

| Role ID | Role | Primary responsibility | May write production code | May merge |
|---|---|---|---:|---:|
| `Lead-01` | Program Director | Dependency graph, priorities, assignments, blockers, schedule, and gate status | No | No |
| `Lead-02` | Technical Integration Lead | Architecture, shared contracts, integration ordering, final source review, and merge train | Limited integration work | Yes |
| `Audit-01` | V1 Requirements Sentinel | Compare every candidate and `main` against issues `#93`–`#102`, functional requirements, NFRs, and acceptance criteria | No | Blocks merge |
| `Audit-02` | Mainline Drift and Quality Sentinel | Detect regressions, false documentation, weakened tests/CI, dependency drift, ABI/schema drift, and platform mismatches | No | Blocks merge |
| `Build-01` | Strategy Engine | Online offline-first, hybrid, adaptive, direction/mode/trigger matrices, and immutable-plan behavior | Yes | No |
| `Build-02` | Android/KMP Platform | Explicit KMP Android variants, `dataloom-android`, WorkManager/Room recovery, process tests, and Android reference consumer | Yes | No |
| `Build-03` | iOS Platform | `dataloom-ios`, connectivity, lifecycle, `BGTaskScheduler`, expiration/cancellation, persistence, security, and KMP iOS reference consumer | Yes | No |
| `Build-04` | Retry and Fault Qualification | Process-loss harness, cross-process probe contention, and AC-FUNC-004 closure | Yes | No |
| `Build-05` | Conflict Engine | Built-in policies, transactional application, durable unresolved conflicts, convergence, audit, and quarantine | Yes | No |
| `Build-06` | Events and Operations | Durable outbox, ordering, replay, retention, exporters, health, read model, and operations adaptor | Yes | No |
| `Build-07` | Asset Synchronization | Streaming, chunking, resume, integrity, quota, private files, cancellation, and cleanup | Yes | No |
| `Build-08` | Release and Test Infrastructure | Contract kit, staged consumers, reference apps, benchmarks, publication, and release evidence | Yes | No |
| `Build-09` | Plugin Platform | Manifests, permissions, lifecycle, compatibility, ordering, isolation, hot disable, and certification | Yes, after dependencies | No |
| `Build-10` | Enterprise Governance | Tenant isolation, RBAC, signed policy packs, audit, residency, fleet/support, and configuration locks | Yes, after dependencies | No |
| `Release-Audit` | Release, Security, and Compliance Sentinel | SBOM, provenance, signing, license, security, staging, rollback, and immutable-candidate evidence | No | Blocks release |

The two audit agents must remain independent of the builders whose work they
assess.

## Execution waves

### Wave 0 — control plane and stabilization

1. Finish the current bounded cache-refresh candidate through normal review.
2. Establish this operating model, ownership rules, and audit templates.
3. Create one central V1 program issue/project and requirement matrix.
4. Freeze Wave 1 public contracts and shared-file ownership.
5. Start no new conflicting feature branches until ownership is assigned.

### Wave 1 — critical shared foundations

Run the following lanes concurrently:

| Lane | Deliverable |
|---|---|
| Strategy | Online offline-first execution, initial direct hybrid executor, and complete adaptive routing to supported concrete strategies |
| Android/KMP | Explicit KMP Android variants, Android aggregate artifact, WorkManager queue callback, and process-relaunch harness |
| iOS | iOS aggregate foundation, lifecycle/connectivity/background contracts, and initial production adapters |
| Retry | Reusable process-loss and probe-contention contract kit |
| Conflict | Built-in policy contracts, durable conflict record, and transactional application boundary |
| Events | Durable event/outbox contract, ordering, acknowledgement, and bounded delivery model |
| Release/Test | Native Android, KMP Android, and KMP iOS staged-artifact reference-app skeletons |

### Wave 2 — close the core engine gates

- Complete all six strategy runtime, failure, restart, and consumer matrices.
- Close native Android, KMP Android, and KMP iOS parity for the core flow.
- Close retry/circuit process-lifecycle qualification.
- Complete built-in conflict policies, persistence, convergence, and restart.
- Complete durable event replay and SDK-wide core instrumentation.
- Run the first end-to-end staged-artifact reference flow on all mandatory
  consumer paths.

The intended closure sequence is `#102`, `#101`, and `#94`, while conflict and
events continue in parallel.

### Wave 3 — assets, plugins, and enterprise

After retry, events, conflict, and platform contracts stabilize:

- implement full asset upload/download, durable resume, integrity, quota,
  secure temporary files, cleanup, and policy hooks;
- implement plugin manifests, deny-by-default permissions, lifecycle,
  compatibility, isolation, deterministic ordering, hot disable, audit, and
  certification; and
- implement tenant isolation, RBAC, signed policy packs, tamper-evident audit,
  residency, support/fleet diagnostics, configuration locks, and LTS/catalog
  governance.

### Wave 4 — full-system qualification

Stop adding features and execute:

- strategy × direction × mode × trigger matrices;
- Android and iOS termination/relaunch scenarios;
- duplicate, concurrency, cancellation, scheduler-failure, and migration tests;
- conflict non-convergence and quarantine tests;
- event ordering, replay, retention, overflow, and exporter-outage tests;
- asset corruption, disk-full, interrupted-transfer, cleanup, and quota tests;
- plugin timeout, crash, cycle, denied-permission, and hot-disable tests;
- tenant-isolation and authorization adversarial tests;
- latency, throughput, memory, storage, battery/background, queue, and asset
  benchmarks; and
- API/ABI, generated Apple header, R8/consumer-rule, artifact-content, and
  staged-consumer qualification.

### Wave 5 — immutable V1 release

1. Build one immutable candidate.
2. Stage the exact artifacts.
3. Run every mandatory platform, compatibility, migration, security, fault,
   benchmark, and external-consumer gate on that commit.
4. Generate checksums, signatures, SBOM, provenance, vulnerability, and license
   evidence.
5. Freeze integration, migration, security, operations, support, and release
   documentation.
6. Record product-owner, legal, security, signing-key, and publication approval.
7. Rehearse rollback and revocation.
8. Promote the same candidate without rebuilding.
9. Run post-publication resolution and smoke verification.

## Isolation and ownership rules

Each builder receives:

```text
one approved issue
one isolated worktree or checkout
one branch
one bounded deliverable
one open pull request maximum
```

Branch format:

```text
agent/<lane>/<issue>-<bounded-slice>
```

Examples:

```text
agent/strategy/102-online-offline-first
agent/platform-android/101-process-relaunch
agent/platform-ios/101-bg-task-runtime
agent/conflict/95-durable-unresolved-store
```

Agents must not share a working directory.

### Shared-file ownership

Only `Lead-02` normally edits these high-conflict files:

```text
README.md
settings.gradle.kts
gradle/libs.versions.toml
.github/workflows/*
public JVM and Kotlin/Native ABI baselines
docs/audits/README.md
publication and release metadata
```

A builder that needs one of these changes records the requested update in its
handoff. The integration lead applies it after reviewing the production code
and generated evidence.

Exceptions require an explicit assignment in the central program record.

## Agent contract

Before work begins, `Lead-01` assigns a contract containing:

```text
Role:
Primary issue:
Acceptance requirements:
Owned source paths:
Forbidden/shared paths:
Input contracts and prerequisites:
Expected production behavior:
Required unit and integration tests:
Required platform evidence:
No-go conditions:
Explicitly out of scope:
Handoff recipient:
```

An agent must stop and escalate when the requested behavior cannot be completed
inside this contract without changing a shared public boundary.

## Required handoff

Every agent posts the following structured handoff to its primary issue or PR:

```text
STATUS:
COMPLETED:
SOURCE CHANGES:
PUBLIC API OR DURABLE FORMAT:
TEST EVIDENCE:
PLATFORM EVIDENCE:
DEPENDENCIES:
KNOWN LIMITS:
AUDIT RISKS:
NEXT OWNER:
```

Allowed status values are:

```text
READY
ACTIVE
PREFLIGHT
REVIEW
AUDIT
MERGE-QUEUE
MERGED
BLOCKED
```

There is no "almost complete" status.

## Verification before commit and PR

GitHub Actions confirms a finished candidate. It must not construct, repair, or
first compile the candidate.

Before the first code commit, the builder must:

1. run focused tests for the changed behavior;
2. compile the affected modules;
3. run `git diff --check` and inspect the complete diff;
4. generate and inspect required API/ABI changes;
5. run the shared build and external-consumer checks;
6. run applicable Android or Apple validation locally where the host permits;
7. verify that dependency, repository, plugin, workflow, schema, and durable
   format changes are intentional; and
8. remove temporary scripts, patches, generated-source helpers, and local
   qualification workflows.

Before opening the PR, a read-only preflight may validate the exact pushed
candidate SHA. The preflight must:

- check out the exact source commit;
- never apply a patch or generate implementation source;
- never mutate the branch;
- never skip a permanent validation lane;
- record the validated commit SHA; and
- use the permanent validation commands.

The PR must contain final production source, tests, reviewed API/ABI and durable
format changes, and current documentation. It must not contain a workflow that
constructs the proposed source tree.

## Review and merge train

Development is parallel; merging is serialized:

```text
Builder completion
    ↓
Local verification
    ↓
Read-only preflight on exact SHA
    ↓
Technical review
    ↓
Requirements audit
    ↓
Mainline and quality audit
    ↓
Permanent PR workflows
    ↓
Integration merge train
    ↓
Squash merge
    ↓
Post-merge main audit
```

Only `Lead-02` merges production changes.

A maximum of three PRs may be in the merge train at one time. Other agents may
continue isolated development and preflight work.

No agent may:

- approve or merge its own PR;
- commit directly to `main`;
- modify workflows merely to pass its PR;
- delete or weaken a test without explicit review;
- claim a release gate is complete;
- update the Market-readiness percentage without audit approval;
- add a dependency without explicit product-owner and architecture approval;
- hide behavior in generated files or CI-applied patches; or
- continue dependent work while an applicable release-blocking audit finding
  remains open.

## Independent audit model

### Audit-01 — requirements sentinel

Audit-01 maintains this live matrix:

| Requirement | Issue | Source | Tests | Android | KMP Android | KMP iOS | Documentation | Verdict |
|---|---|---|---|---|---|---|---|---|

It runs:

1. before a workstream starts;
2. before its PR opens;
3. on the final PR SHA; and
4. after the merge reaches `main`.

It detects contracts without runtime behavior, planners without executors,
durable claims without restart proof, common-only evidence, silently accepted
unsupported paths, missing failure matrices, and premature completion claims.

### Audit-02 — mainline drift and quality sentinel

After each merge, Audit-02 compares the previous audited `main` SHA with the
current SHA and checks:

- strategy-plan/provider-operation correspondence;
- immutable accepted-plan behavior;
- queue-before-scheduler and transactional ordering;
- cancellation, retry, conflict, and partial-effect classification;
- payload, credential, and sensitive-data minimization;
- dependency and module-direction drift;
- internal API leaks, public ABI, and durable schema/format changes;
- native Android, KMP Android, and KMP iOS parity;
- deleted or weakened tests;
- skipped or altered validation conditions; and
- documentation and dashboard accuracy.

### Audit blocker format

An auditor records a blocker using:

```text
Label: audit:blocker
Severity: release-blocking | wave-blocking | local
Introduced by SHA:
Affected requirements:
Evidence:
Required correction:
Owning agent:
Dependent work to pause:
```

`Lead-02` must not merge while an applicable `audit:blocker` remains unresolved.

## CI and quality objectives

Target operational metrics:

```text
PR first-pass success                  >= 90%
Direct commits to main                 0
CI-generated implementation changes   0
Temporary workflow changes in PRs      0
Undocumented dependencies              0
Requirements traceability              100%
Post-merge regressions                 0
```

When CI fails:

1. inspect the first meaningful failure;
2. distinguish source, environment, and transient failures;
3. correct and validate the source locally;
4. push one deliberate correction; and
5. rerun only the failed job when a transient cause is demonstrated.

Do not blindly rerun unchanged failures.

## Definition of done for a bounded slice

A slice is complete only when:

- its exact accepted behavior is implemented in production source;
- success, failure, cancellation, duplicate, concurrency, persistence, and
  restart paths affected by the change are tested;
- forbidden provider calls and silent fallbacks are tested;
- public API/ABI and durable-format changes are reviewed;
- native Android, KMP Android, and KMP iOS evidence is provided where required;
- the no-new-third-party rule is verified;
- documentation describes current behavior and explicit limitations;
- both audit agents accept the final SHA; and
- permanent required workflows pass on that same SHA.

A bounded slice may be merged while its parent V1 gate remains open. The PR and
documentation must state the remaining parent-gate blockers precisely.

## Immediate program actions

1. Complete review and merge handling for the current durable cache-refresh PR.
2. Create the central V1 program issue/project and requirement matrix.
3. Assign Wave 1 roles, paths, contracts, and handoff recipients.
4. Start the Android process-recovery, iOS platform, strategy, retry,
   conflict, event, and reference-consumer lanes in parallel.
5. Run legal/publication decisions and customer validation alongside
   engineering rather than waiting for engineering completion.

## Expected acceleration

Parallel AI-assisted execution does not scale linearly because architecture,
public API/ABI, merge ordering, platform devices, macOS validation, full-system
qualification, legal approval, and pilots remain shared bottlenecks.

The planning target for ten to twelve coordinated active agents, two leads, and
two independent auditors is approximately:

- **45–65 focused engineering working days** for an immutable V1 release
  candidate, assuming reliable runners, prompt decisions, and no architecture
  reset; and
- **10–14 calendar weeks** for full technical qualification and publication,
  with external market evidence potentially extending complete market
  readiness.

These are planning ranges, not release promises. The release remains NO-GO
until the immutable-candidate gate is accepted.
