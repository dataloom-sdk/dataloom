# DL-AUDIT-005 — current V1 conformance and regression audit

## Executive decision

**DataLoom V1.0 remains a NO-GO.**

The repository has advanced materially since DL-AUDIT-004, especially in retry
classification, standard backoff, deterministic jitter, retry budgets, bounded
hints, circuit state, half-open recovery, and Android durability. Those bounded
slices are useful and several are well tested. They do not satisfy the frozen
V1 acceptance contract as a whole.

The important distinction is:

- a pull request may correctly implement its declared bounded slice and pass its
  current CI lanes; and
- the parent V1 requirement may still be partial because required runtime
  assembly, persistence, platform parity, operations, observability, security,
  or end-to-end qualification is absent.

Green CI is evidence for the tested graph. It is not evidence that unassembled
or unimplemented behavior exists.

## Audit identity

| Item | Value |
|---|---|
| Primary baseline | `main` at `752a355ef7767a51d060bf423fce1b671077538d` |
| Last substantive merged feature | PR #116, commit `77facc53e602c76da8d497983e608672f9b1d5ce` |
| Android circuit persistence candidate | PR #117, audited separately while open |
| Corrective candidate in this audit branch | workflow-timeout enforcement regression fix |
| Governing scope | #92, DL-038 frozen V1.0 GA scope |
| Parent production audit | #91 / DL-AUDIT-004 |
| Retry acceptance owner | #94 / DL-040 |
| Platform parity owner | #101 / DL-039A |
| Six-strategy owner | #102 / DL-039B |

The two commits after PR #116 add and immediately remove an accidental audit
placeholder. They have no net tree-content effect; this audit evaluates the
resulting tree rather than treating that history noise as implementation.

## Method

The audit compared:

1. frozen issue acceptance criteria;
2. production source and actual production references;
3. tests and durable migration evidence;
4. public JVM/Kotlin-Native ABI and external-consumer checks;
5. Android and Apple workflow evidence;
6. current documentation claims; and
7. missing runtime assembly, persistence, operations, and platform paths.

Classification rules:

- **Implemented:** essential production behavior exists and has meaningful tests.
  This does not by itself mean GA-qualified.
- **Partial:** useful implementation exists, but at least one mandatory semantic,
  durability, platform, security, operations, or qualification gate is absent.
- **Missing:** no production implementation of the required capability exists.

## High-confidence findings

### 1. Merged retry work is real, but DL-040 is not accepted

PRs #108–#116 implemented substantial common-runtime behavior. The following
are no longer reasonable to describe as wholly missing:

- central fail-closed failure classification;
- immediate, fixed, linear, and exponential backoff;
- maximum-attempt enforcement in the standard policy;
- full and equal jitter with deterministic injected randomness;
- elapsed-time and cumulative-delay budget contracts and queue persistence;
- bounded typed provider/server hints;
- independent timeout contracts and a timeout coordinator;
- closed/open/half-open circuit state with atomic compare-and-set persistence
  SPI;
- circuit permission and provider/scheduler adapters;
- durable half-open probe leases and abandoned-probe generation recovery.

DL-040 nevertheless remains open and correctly remains a release blocker.

### 2. A real workflow-timeout bypass existed despite green PR validation

`RetryTimeoutCoordinator.execute` returned directly whenever the requested
boundary timeout was null. That return occurred before workflow-deadline
calculation. Therefore, a configured workflow timeout was silently ignored for
an operation whose specific boundary was unconfigured.

Example before correction:

```text
workflowTimeout = 2 seconds
providerTimeout = null
workflowStartedAt = present
execute(PROVIDER) -> operation ran directly without timeout executor
```

This violates timeout separation and complete-workflow deadline enforcement.
The corrective candidate on this audit branch:

- evaluates the workflow deadline even when the requested boundary is absent;
- stops expired or clock-regressed workflows before invocation;
- uses remaining workflow time when it is the only configured limit; and
- reports `WORKFLOW` as the limiting timeout when the workflow window is shorter
  than the requested boundary.

Regression tests cover unconfigured-boundary enforcement, boundary/workflow
precedence, expiration, and clock regression.

### 3. Timeout support is still only a contract/coordinator slice

No production implementation of `RetryTimeoutExecutor` is present, and
`RetryTimeoutCoordinator` has no production assembly reference outside its own
source and tests. Provider/protocol adapters remain responsible for actual
connection, request, idle, and cancellation enforcement.

FR-RETRY-006 is therefore **Partial**, even after the bypass fix.

### 4. Circuit adapters are not assembled into the main runtime

`CircuitBreakerProviderOperationAdapter` and
`CircuitBreakerRetrySchedulingAdapter` exist, have tests, and compile for
external consumers. Repository references show them only in their definitions,
tests, and external-consumer probes. The synchronization pipelines,
`SynchronizationRetryOrchestrator`, queue execution, storage, and transport
assembly do not use them.

The circuit engine can be consumed deliberately by an application, but it is
not yet the automatic V1 runtime gate required by #94.

### 5. Android circuit persistence needed audit corrections before acceptance

The first PR #117 draft was not acceptable as-is. Audit review identified and
corrected the following on that branch:

1. Android CI was hard-coded to Room schema `2.json` after the database advanced
   to version 3. The gate now derives the current Room version and identity hash
   from KSP output and verifies the matching committed schema.
2. The migration test reopened the current version-3 database while registering
   only `MIGRATION_1_2`. It now installs the complete production migration set
   and contains explicit version 2 to 3 data-preservation evidence.
3. Malformed durable circuit rows and record-version exhaustion were initially
   collapsed into a recoverable database error. They now fail closed as
   sanitized, non-recoverable state errors.
4. Initial JVM test methods inferred non-`Unit` return types and were invalid
   JUnit methods. The signatures were corrected and the focused evidence lane
   passed.

PR #117 remains an Android-only candidate. It cannot complete FR-RETRY-009
because production KMP iOS circuit persistence and full runtime assembly remain
missing.

### 6. Suspected timestamp-overflow defects were rejected after invariant review

An intermediate review suspected overflow in non-negative timestamp subtraction
and offline-deferral addition. `DataLoomInstant` rejects negative epoch values,
and `SchedulingDelay` is non-negative. Under those public invariants:

- later-minus-earlier elapsed calculations cannot exceed `Long.MAX_VALUE`; and
- addition overflow is detectable through a negative wrapped result.

Those suspected defects are **not** recorded as findings. This audit retains
only evidence-supported defects.

### 7. No placeholder implementation markers were found in current production code

Repository searches found no production `NotImplementedError`, ignored/disabled
tests, or `assertTrue(true)` placeholder tests. `TODO`, `FIXME`, `@Ignore`, and
`@Disabled` hits were documentation or ordinary prose rather than active
implementation markers.

This is positive code hygiene evidence, but it does not reduce the mandatory
missing-capability count.

## Updated mandatory 72-requirement score

The original DL-AUDIT-004 score was **1 implemented, 16 partial, 55 missing**.
After reclassifying the merged retry work while retaining unchanged evidence for
the other five families, the current score is:

| Family | Implemented | Partial | Missing | Total |
|---|---:|---:|---:|---:|
| Retry and circuit breaker | 3 | 8 | 1 | 12 |
| Conflict handling | 1 | 5 | 6 | 12 |
| Events and operational delivery | 0 | 6 | 6 | 12 |
| Asset transfer | 0 | 0 | 12 | 12 |
| Plugin platform | 0 | 0 | 12 | 12 |
| Enterprise administration/governance | 0 | 0 | 12 | 12 |
| **Total** | **4** | **19** | **49** | **72** |

This is a requirement-conformance score, not a percentage-complete estimate.
A single mandatory partial or missing capability is a V1 no-go under #92.

## Detailed retry and circuit matrix

| ID | Requirement | Current status | Evidence and remaining gap |
|---|---|---|---|
| FR-RETRY-001 | Failure classification | **Implemented** | Central ordered pre-scan blocks unknown, non-recoverable, authentication, authorization, serialization, validation, configuration, policy, conflict, and security failures before custom policy or scheduler invocation. Cancelled results are not evaluated as retry failures. |
| FR-RETRY-002 | Retry strategies | **Implemented** | Built-in immediate, fixed, linear, and exponential strategies plus application-provided `RetryPolicy`; deterministic and overflow-clamped. |
| FR-RETRY-003 | Jitter | **Implemented** | None/full/equal jitter, deterministic injected random source, rejection sampling, and reproducibility tests exist. |
| FR-RETRY-004 | Attempt and elapsed limits | **Partial** | Standard maximum-attempt enforcement and central elapsed/cumulative budgets exist. Queue state is durable. Direct scheduler orchestration returns next budget state but does not provide a built-in durable owner, and complete platform/end-to-end qualification is absent. |
| FR-RETRY-005 | Provider/server hints | **Partial** | Typed bounded hints and minimum-delay enforcement exist. Shared runtime intentionally does not parse protocol headers; no standard HTTP/provider adapter demonstrates normalized `Retry-After` handling end-to-end. |
| FR-RETRY-006 | Timeout separation | **Partial** | Six timeout kinds, configuration, execution request/result, and coordinator exist. The workflow-bypass defect is corrected on this audit branch. A production executor and actual provider/policy/workflow assembly are still absent. |
| FR-RETRY-007 | Circuit breaker | **Partial** | Deterministic closed/open/half-open state, threshold/window, CAS SPI, execution gate, and provider/scheduler adapters exist. They are not assembled into transport/storage/queue/synchronization runtime paths; iOS durability is absent. |
| FR-RETRY-008 | Half-open probe | **Partial** | One controlled generation, active-lease rejection, exact-deadline replacement, stale-generation protection, and probe expiry exist. True concurrent/multi-process and platform end-to-end qualification is absent. |
| FR-RETRY-009 | Retry/circuit persistence | **Partial** | Queue attempts and budget state survive Room restart/deferral/recovery. PR #117 adds candidate Android circuit persistence and migration. KMP iOS persistence, all-state aggregation, and complete runtime restart evidence are absent. |
| FR-RETRY-010 | Retry observability | **Partial** | Retry events and canonical errors provide limited evidence. No complete lifecycle taxonomy, bounded-cardinality metrics, structured logging, tracing, exporter, or operational read model exists. |
| FR-RETRY-011 | Manual retry | **Missing** | No authorized, idempotent, audited manual retry/requeue service preserving immutable history exists. |
| FR-RETRY-012 | Non-retryable protection/reclassification | **Partial** | Automatic retry protection is implemented. The authorized, audited reclassification path required by #94 does not exist. |

### DL-040 acceptance checklist

| Acceptance item | Verdict |
|---|---|
| FR-RETRY-001–012 mapped to source/tests | **Partial** — this audit maps them, but several remain partial/missing |
| Backoff and jitter | **Pass as bounded common-runtime slice** |
| Circuit opens/rejects/half-opens/recovers | **Pass in focused state-machine tests** |
| Full Book 2 AC-FUNC-004 through real runtime providers | **Fail / not implemented** |
| Restart survival for attempts and queue budgets | **Pass on Android queue path** |
| Restart survival for circuit state | **Android candidate in PR #117; KMP iOS missing** |
| Boundary/property coverage | **Partial** |
| Real concurrency/multi-process probe evidence | **Fail / missing** |
| Manual retry/reclassification authorization and audit | **Fail / missing** |
| Metrics/logs/traces with redaction/cardinality controls | **Fail / missing** |
| Complete native Android, KMP Android, and KMP iOS parity | **Fail / missing** |

## Conflict family

No merged work after DL-AUDIT-004 completes this family. Status remains
**1 implemented, 5 partial, 6 missing**.

| ID | Requirement | Status | Current finding |
|---|---|---|---|
| FR-CONFLICT-001 | Conflict detection | Partial | Custom detector exists; standard version/vector/timestamp/ETag detection and integration are absent. |
| FR-CONFLICT-002 | Built-in strategies | Missing | Client-wins, server-wins, LWW/timestamp, field merge, reject, and manual policies are absent. |
| FR-CONFLICT-003 | Custom resolver | Implemented | Contract, registry, exact lookup, invocation, result model, and tests exist. |
| FR-CONFLICT-004 | Resolver context | Partial | Local/remote evidence exists; base value and complete version/tenant/trace/redaction context are incomplete. |
| FR-CONFLICT-005 | Resolution result | Partial | Typed decisions exist; atomic application, retry linkage, reject, and user-action lifecycle are incomplete. |
| FR-CONFLICT-006 | Determinism validation | Partial | Scripted utilities exist; no resolver certification/repeated-input gate. |
| FR-CONFLICT-007 | Conflict audit | Missing | No immutable resolution audit provider. |
| FR-CONFLICT-008 | Unresolved persistence | Missing | Deferred/manual conflicts do not survive restart. |
| FR-CONFLICT-009 | Loop prevention | Missing | No fingerprint, attempt limit, convergence, or quarantine. |
| FR-CONFLICT-010 | Strategy precedence | Missing | No entity/workflow/tenant/global hierarchy. |
| FR-CONFLICT-011 | Sensitive-field handling | Partial | Documentation guidance exists; centralized enforced redaction does not. |
| FR-CONFLICT-012 | Resolution metrics | Missing | No production metrics. |

## Events and operational-delivery family

No merged work after DL-AUDIT-004 completes this family. Status remains
**0 implemented, 6 partial, 6 missing**.

| ID | Requirement | Status | Current finding |
|---|---|---|---|
| FR-EVENT-001 | Canonical envelope | Partial | Typed events exist; complete versioned ID/type/source/trace/causation envelope is absent. |
| FR-EVENT-002 | Event categories | Missing | No stable domain/lifecycle/system/audit/telemetry/diagnostic taxonomy. |
| FR-EVENT-003 | Ordered workflow events | Partial | One dispatch is sequential; authoritative cross-call/restart ordering is absent. |
| FR-EVENT-004 | At-least-once delivery | Missing | No outbox, acknowledgement, replay, or redelivery. |
| FR-EVENT-005 | Subscription filtering | Missing | No bounded type/workflow/tenant/severity/category filters. |
| FR-EVENT-006 | Back-pressure | Missing | No bounded buffers or slow-consumer policy. |
| FR-EVENT-007 | Sensitive-data redaction | Partial | Safe model guidance exists; centralized enforcement is absent. |
| FR-EVENT-008 | Notification hooks | Partial | General observers exist; governed hook/delivery semantics are incomplete. |
| FR-EVENT-009 | Schema evolution | Missing | No event schema version/upcaster/compatibility kit. |
| FR-EVENT-010 | Event persistence | Missing | No durable operational/audit provider or retention/query API. |
| FR-EVENT-011 | Event correlation | Partial | Request/workflow identifiers exist; complete causation/trace propagation is absent. |
| FR-EVENT-012 | Consumer isolation | Partial | Ordinary exceptions are isolated; time/buffer/bulkhead limits are absent. |

## Asset-transfer family

Status remains **0 implemented, 0 partial, 12 missing**.

| ID | Requirement | Status |
|---|---|---|
| FR-ASSET-001 | Versioned asset manifest | Missing |
| FR-ASSET-002 | Chunked upload/download | Missing |
| FR-ASSET-003 | Durable resume | Missing |
| FR-ASSET-004 | Chunk and whole-object integrity | Missing |
| FR-ASSET-005 | Bounded-memory streaming | Missing |
| FR-ASSET-006 | Parallelism/fairness controls | Missing |
| FR-ASSET-007 | Compression policy | Missing |
| FR-ASSET-008 | Encryption metadata/key references | Missing |
| FR-ASSET-009 | Private temporary-file safety | Missing |
| FR-ASSET-010 | Storage quotas | Missing |
| FR-ASSET-011 | Cancellation/resume cleanup | Missing |
| FR-ASSET-012 | Content policy/scan/quarantine hooks | Missing |

## Plugin-platform family

Status remains **0 implemented, 0 partial, 12 missing**. The provider SPI is not
the mandatory plugin platform.

| ID | Requirement | Status |
|---|---|---|
| FR-PLUGIN-001 | Stable extension points | Missing |
| FR-PLUGIN-002 | Plugin manifest | Missing |
| FR-PLUGIN-003 | Deny-by-default enablement | Missing |
| FR-PLUGIN-004 | Lifecycle | Missing |
| FR-PLUGIN-005 | Permission model | Missing |
| FR-PLUGIN-006 | Execution bounds | Missing |
| FR-PLUGIN-007 | Deterministic ordering/dependencies | Missing |
| FR-PLUGIN-008 | Failure isolation | Missing |
| FR-PLUGIN-009 | Hot disable/drain | Missing |
| FR-PLUGIN-010 | Compatibility validation | Missing |
| FR-PLUGIN-011 | Audit | Missing |
| FR-PLUGIN-012 | Certification kit | Missing |

## Enterprise administration and governance family

Status remains **0 implemented, 0 partial, 12 missing**. Identifiers such as
`TenantId` do not enforce tenancy or governance.

| ID | Requirement | Status |
|---|---|---|
| FR-ENT-001 | Tenant isolation | Missing |
| FR-ENT-002 | Signed/versioned policy packs | Missing |
| FR-ENT-003 | Tamper-evident audit trail | Missing |
| FR-ENT-004 | Role-based operations | Missing |
| FR-ENT-005 | Fleet diagnostics | Missing |
| FR-ENT-006 | LTS/support policy | Missing |
| FR-ENT-007 | Certified provider/plugin catalog | Missing |
| FR-ENT-008 | Offline audit buffer | Missing |
| FR-ENT-009 | Residency controls | Missing |
| FR-ENT-010 | Operational override | Missing |
| FR-ENT-011 | Redacted support bundle | Missing |
| FR-ENT-012 | Enterprise configuration lock | Missing |

## Six-strategy engine audit

The six strategy contracts and deterministic planner are meaningful progress,
but #102 remains correctly open.

| Strategy/capability | Current verdict |
|---|---|
| Versioned profiles and deterministic planning for all six | Partial foundation implemented |
| Network-only direct PUSH/PULL/BIDIRECTIONAL | Implemented bounded runtime slice; full events/platform qualification remain |
| Remote-first direct execution and typed fallback | Implemented bounded runtime slice; durable replay/circuit/conflict/events/platform qualification remain |
| Offline-first | Complete atomic intent/outbox admission and reconciliation missing |
| Cache-first | Fresh/stale/miss/stale-while-refresh runtime missing |
| Hybrid | Primary/fallback/returned-source/persistence/coherence runtime missing |
| Adaptive | Durable deterministic runtime selection over approved profiles missing |
| Persisted effective strategy/plan/config version | Missing |
| Retry/restart cannot change strategy | Not proven |
| Full profile parity on native Android/KMP Android/KMP iOS | Missing |

## Platform audit

| Consumer path | Current verdict | Mandatory gap |
|---|---|---|
| Native Android | Partial foundation | Published aggregate/consumer path and complete subsystem E2E qualification missing |
| KMP Android | Missing qualified path | No explicit Android KMP target/variant or external KMP Android consumer |
| KMP iOS | Partial compile foundation | No production `dataloom-ios`, lifecycle, connectivity, background scheduling, durable persistence, files/security, or executable consumer |
| Optional native Swift | Compile-smoke foundation | If shipped, requires approved public-only surface and full distribution qualification |

PR #117 improves native Android circuit durability only. It does not satisfy the
cross-platform gate in #101.

## Publication and release audit

V1 publication remains a no-go because the repository still lacks the complete
candidate-specific evidence required by #100, including:

- final approved artifact graph and BOM publication;
- staged external consumers for every mandatory path;
- finalized license and publication namespace ownership;
- signing, checksums, SBOM, provenance, and release credentials;
- complete migrations and restart/fault/concurrency qualification;
- security and dependency evidence;
- final support/LTS policy and runbooks;
- immutable candidate approval and promotion without rebuilding.

The target date cannot override these gates.

## What is accepted today

The following statements are supported:

- DataLoom has a strong shared synchronization and provider foundation.
- The queue retry-attempt corruption fixed by PR #107 is corrected.
- Standard retry backoff and deterministic jitter are implemented as reusable
  common-runtime policies.
- Queue-backed elapsed/cumulative retry budgets are durably represented on
  Android.
- Bounded typed retry hints are implemented at the normalized contract level.
- Circuit closed/open/half-open transitions and abandoned-probe leases are
  implemented in common runtime with focused tests.
- The circuit execution gate preserves whether an operation already ran when a
  later state update fails.
- Android Room circuit persistence is a credible candidate in PR #117 after
  audit corrections.
- JVM, Android, Kotlin/Native, XCFramework, header, and Swift-smoke checks provide
  useful compatibility evidence for the code they exercise.

## What is not accepted today

The following claims would be incorrect:

- “DL-040 is complete.”
- “All six synchronization strategies are complete.”
- “DataLoom has full Android/iOS parity.”
- “The circuit breaker automatically protects all provider/runtime paths.”
- “Timeouts are production-enforced across all boundaries.”
- “Manual retry/reclassification is governed and audited.”
- “Events and observability are production complete.”
- “Assets, plugins, and enterprise governance are ready.”
- “V1 is production-ready or publishable.”

## Ordered corrective plan

1. Merge only after PR #117 is green on final head and its migration/integrity
   evidence is reviewed.
2. Merge the workflow-timeout regression fix from this audit branch after its
   JVM/Android/Apple lanes pass.
3. Add a production timeout executor for each supported runtime and wire
   connection/request/idle/provider/policy/workflow boundaries into actual
   operations.
4. Assemble circuit gates into transport, storage, queue, retry, and strategy
   execution; define exact scope selection and precedence.
5. Implement KMP iOS circuit/retry persistence and relaunch migration.
6. Implement retry/circuit events, bounded metrics, logs, traces, reason codes,
   redaction, and correlation.
7. Implement authorized, idempotent, audited manual retry/reclassification and
   circuit administration.
8. Add real concurrent, multi-process, process-death, and AC-FUNC-004 tests.
9. Complete conflict, durable event, asset, plugin, and enterprise families in
   dependency order.
10. Complete the six strategy runtimes and native Android/KMP Android/KMP iOS
    parity before immutable-candidate release qualification.

## Final audit verdict

The implementation is **not wrong as a whole**; it is a substantial and useful
pre-V1 foundation. Some bounded PRs met their declared scope. The repository is
wrong only when those bounded slices are interpreted as satisfying the frozen
parent V1 acceptance criteria.

The truthful current statement is:

> DataLoom has materially improved retry and circuit foundations, with Android
> circuit persistence under review, but the full retry engine, six-strategy
> product, cross-platform runtime, and four other mandatory capability families
> remain incomplete. V1 release status is NO-GO.
