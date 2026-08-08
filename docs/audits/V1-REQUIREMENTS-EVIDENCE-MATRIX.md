# V1 requirements and evidence matrix

Status: **active Audit-01 control record**  
Initial audited `main`: `c7e16b71d711cb7c82406c84aca76428f44e0328`  
Current audited `main`: `241ead7a5daec4b4fe1e6c88ffc081610dad55bd`  
Owner: `Audit-01` — V1 Requirements Sentinel  
Program control: issue `#188`; audit work item: issue `#189`

## Purpose

This matrix prevents contracts, planner branches, compile-only targets, or isolated
green tests from being mistaken for a completed V1 capability. A requirement is
accepted only when production source, executable tests, mandatory platform
evidence, persistence/restart behavior where applicable, security/redaction
evidence, and current documentation all refer to the same reviewed commit.

Historical audits remain immutable. This file is the live index; detailed
point-in-time verdicts are recorded in separately dated reconciliation audits.

## Verdict rules

| Verdict | Meaning |
|---|---|
| **ACCEPTED** | Complete issue acceptance criteria have executable evidence on one immutable reviewed commit |
| **IMPLEMENTED / QUALIFICATION BLOCKED** | Production behavior exists, but required process/platform/security/consumer proof is absent |
| **PARTIAL** | A useful subset exists; the full semantic guarantee is not implemented or qualified |
| **MISSING** | No accepted production subsystem exists |
| **BLOCKED / NO-GO** | A prerequisite or release condition prevents acceptance |

## Current release-gate matrix

| Gate | Scope | Verdict | Accepted evidence | Remaining blocker |
|---|---|---|---|---|
| #93 | Foundations, artifacts and compatibility | COMPLETE | Accepted API/module/configuration/state/ABI/redaction/wire foundations | No blocker inside this gate |
| #94 | Retry and circuit breaker | QUALIFICATION BLOCKED | FR-RETRY-001–012 implementation foundation | Real Android/Apple termination and relaunch, supported cross-process contention, complete three-consumer AC-FUNC-004 |
| #95 | Conflict engine | PARTIAL | Custom detector/resolver and invocation foundation | Built-ins, atomic application, unresolved persistence, audit, convergence/quarantine, precedence and platform proof |
| #96 | Events/observability/operations | PARTIAL | Envelope/redaction/wire, in-process dispatch and bounded retry/circuit telemetry | Durable outbox/order/ack/replay/retention/filtering, SDK-wide adoption and operations read model |
| #97 | Asset synchronization | MISSING | No accepted production subsystem | Complete FR-ASSET-001–012 |
| #98 | Plugin platform | MISSING | Provider SPI is not plugin platform | Complete FR-PLUGIN-001–012 |
| #99 | Enterprise governance | MISSING | Tenant identifiers and limited administration foundation | Complete FR-ENT-001–012 |
| #100 | Immutable V1 release | BLOCKED / NO-GO | Continuous validation foundations | All implementation/platform/security/legal/publication/market gates on one candidate |
| #101 | Native Android/KMP Android/KMP iOS parity | PARTIAL | Android adapters, Native targets, Apple stores/XCFramework smoke | Explicit KMP Android variants, platform aggregates, production iOS adapters, staged consumers and parity kit |
| #102 | Six built-in strategies | PARTIAL | All-six planner plus executable network/remote/cache and durable plan foundations | Online offline-first, hybrid, remaining cache/adaptive paths, events/conflict/restart/platform matrices |

> The formal completion score remains **1 of 10 gates (10%)**. This is not a code-volume estimate.

## Six-strategy matrix

| ID | Strategy | Required invariant | Gate | Verdict | Accepted evidence | Remaining blocker | Owners |
|---|---|---|---|---|---|---|---|
| S-OF-01 | Offline-first | Atomic eligible local intent plus durable work admission before success | #102 | Partial | Deferred atomic provider contract/invocation, immutable decision/plan persistence and queued replay exist | Online admission plus immediate ownership; Android/KMP Android/KMP iOS atomic providers; process-loss matrix | Build-01 / Build-02 / Build-03 |
| S-RF-01 | Remote-first | Remote attempt first; fallback only for explicitly classified outcomes | #102 | Partial | Direct PUSH/PULL/BIDIRECTIONAL, typed fallback and provider protection exist | Durable defer/restart, conflict persistence, durable events and full platform matrix | Build-01 / Build-05 / Build-06 |
| S-CF-01 | Cache-first | Fresh/stale/miss/refresh/data-origin behavior is explicit | #102 | Partial | Cache verification, direct serve, remote miss, inline refresh and audited durable admission exist | Process relaunch; independent protected queue/scheduler adapters; bidirectional refresh; coherence/events | Build-01 / Build-02 / Build-03 |
| S-NO-01 | Network-only | Transport-only execution with zero storage or queue side effects | #102 | Partial | Direct PUSH/PULL/BIDIRECTIONAL and plan-aware zero-storage/queue behavior exist | Complete event/result metadata, bounded retry decision, all consumer/platform matrices | Build-01 / Build-06 / Build-08 |
| S-HY-01 | Hybrid | Finite primary/fallback/persistence/coherence/reconciliation runtime | #102 | Missing runtime | Versioned profile, deterministic plan and accepted continuation foundation exist | Direct executor, fallback/coherence/conflict application, durable branch recovery and platform matrix | Build-01 / Build-05 |
| S-AD-01 | Adaptive | Deterministically select and freeze one approved concrete strategy | #102 | Partial | Bounded selection, plan-derived capabilities and persistence/replay exist | Execution for online offline-first/hybrid, authorized re-evaluation, normalized evidence and full matrix | Build-01 |

### Strategy cross-product acceptance

Every strategy must eventually record evidence for the applicable cross product:

```text
strategy
× PUSH / PULL / BIDIRECTIONAL
× FULL / DELTA
× direct / manual / automatic / periodic / event / lifecycle / connectivity
× online / offline / unknown connectivity
× healthy / degraded / open-circuit providers
× success / typed failure / cancellation / duplicate / restart / migration
× native Android / KMP Android / KMP iOS
```

Unsupported combinations must have explicit typed evidence; they must not be silently skipped.

## Platform parity matrix

| Platform requirement | Current status | Acceptance evidence still required | Owner |
|---|---|---|---|
| Explicit KMP Android variants for shared artifacts | Missing | Published-style commonMain + Android variant resolution | Build-02 / Lead-02 |
| `dataloom-android` aggregate artifact | Missing | Narrow dependency graph, AAR/POM/module/R8 checks and native Android consumer | Build-02 / Build-08 |
| `dataloom-ios` production boundary | Missing | Lifecycle, connectivity, BGTask, files/security/persistence aggregation | Build-03 |
| Native Android reference consumer | Missing | Executable staged-artifact app and contract kit | Build-08 |
| KMP Android reference consumer | Missing | Executable commonMain/Android staged-artifact app | Build-02 / Build-08 |
| KMP iOS reference consumer | Missing | Executable simulator/device flow from commonMain/iOS source sets | Build-03 / Build-08 |
| Android real process termination/relaunch | Missing | OS process kill/relaunch and state/replay assertions | Build-02 / Build-04 |
| Apple host termination/relaunch | Missing | Production-equivalent host lifecycle and persisted-state replay | Build-03 / Build-04 |
| No public/internal type leakage | Partial | Gradle metadata, KLib, Objective-C/Swift header and consumer audit on final candidate | Lead-02 / Audit-02 |

## Retry and circuit requirements

| Requirement | Required behavior | Current evidence verdict | Platform/restart status | Owner |
|---|---|---|---|---|
| `FR-RETRY-001` | Central failure classification | **Implemented; platform closure pending** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-002` | Immediate/fixed/linear/exponential plus custom policies | **Implemented** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-003` | Deterministic jitter with injectable randomness | **Implemented** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-004` | Attempt and elapsed/cumulative budgets | **Implemented; process-loss proof pending** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-005` | Bounded provider/server hints | **Implemented; adapter-specific proof pending** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-006` | Connection/request/idle/workflow/provider/policy timeout separation | **Implemented; failure-injection matrix pending** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-007` | Durable closed/open/half-open circuit breaker | **Implemented; mandatory-path proof pending** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-008` | Controlled durable half-open probe lease | **Implemented; true cross-process proof pending** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-009` | Durable retry/circuit/next-attempt state | **Implemented; OS termination/relaunch pending** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-010` | Bounded redacted retry/circuit observability | **Implemented for retry/circuit scope** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-011` | Authorized idempotent manual retry/requeue | **Implemented; process-loss proof pending** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |
| `FR-RETRY-012` | Fail-closed non-retryable protection and audited reclassification | **Implemented; platform proof pending** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-04 / Build-02 / Build-03 |

## Conflict requirements

| Requirement | Required behavior | Current evidence verdict | Platform/restart status | Owner |
|---|---|---|---|---|
| `FR-CONFLICT-001` | Standard detection utilities | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-002` | Built-in client/server/LWW/timestamp/field/reject/manual policies | **Missing** | No platform evidence | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-003` | Custom resolver with redaction-safe context | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-004` | Resolved/unresolved/retry/reject/user-action outcomes | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-005` | Resolver determinism validation/certification | **Missing** | No platform evidence | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-006` | Immutable conflict audit record | **Missing** | No platform evidence | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-007` | Durable unresolved/manual conflict state | **Missing** | No platform evidence | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-008` | Fingerprint, convergence limit, quarantine and loop prevention | **Missing** | No platform evidence | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-009` | Entity > workflow > tenant > global precedence | **Missing** | No platform evidence | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-010` | Sensitive-field minimization and redaction | **Partial foundation** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-011` | Bounded conflict metrics | **Missing** | No platform evidence | Build-05 / Build-02 / Build-03 / Build-06 |
| `FR-CONFLICT-012` | Transactional detection/resolution/apply/checkpoint/audit/event boundary | **Missing** | No platform evidence | Build-05 / Build-02 / Build-03 / Build-06 |

## Event, observability and operations requirements

| Requirement | Required behavior | Current evidence verdict | Platform/restart status | Owner |
|---|---|---|---|---|
| `FR-EVENT-001` | Versioned canonical envelope | **Implemented foundation** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-002` | Domain/lifecycle/system/audit/telemetry/diagnostic categories | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-003` | Authoritative per-workflow ordering | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-004` | Optional durable at-least-once delivery | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-005` | Acknowledgement and replay | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-006` | Retention policy and expiry | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-007` | Subscription filtering | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-008` | Bounded buffers/back-pressure/overflow | **Partial retry/circuit exporters only** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-009` | Central redaction and minimization | **Implemented foundation** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-010` | Notification hooks and schema evolution/upcast | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-011` | Persisted operational/audit events and consumer isolation | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |
| `FR-EVENT-012` | SDI-owned operational query/read model | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-001` | Structured logging | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-002` | Bounded-cardinality metrics | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-003` | Tracing and correlation propagation | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-004` | Exporter/adaptor isolation | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-005` | Sampling controls | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-006` | Telemetry failure isolation | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-007` | Health/degradation model | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-008` | Stable diagnostic reason codes | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-009` | Monotonic duration measurement | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-010` | Redacted support snapshots | **Partial** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-011` | Operations dashboard/adaptor | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |
| `NFR-OBS-012` | SDK-wide instrumentation coverage | **Missing** | No platform evidence | Build-06 / Build-02 / Build-03 |

## Asset synchronization requirements

| Requirement | Required behavior | Current evidence verdict | Platform/restart status | Owner |
|---|---|---|---|---|
| `FR-ASSET-001` | Versioned asset manifest | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-002` | Chunked upload | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-003` | Chunked download | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-004` | Durable resumable sessions and restart | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-005` | Per-chunk and whole-object integrity | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-006` | Bounded-memory streaming source/sink | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-007` | Parallelism limits and fairness | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-008` | Compression negotiation/configuration | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-009` | Encryption metadata without key material | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-010` | Private temporary files and atomic promotion | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-011` | Quota, cancellation and cleanup | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |
| `FR-ASSET-012` | Content allow/deny/scan/quarantine hooks | **Missing** | No platform evidence | Build-07 / Build-02 / Build-03 |

## Plugin platform requirements

| Requirement | Required behavior | Current evidence verdict | Platform/restart status | Owner |
|---|---|---|---|---|
| `FR-PLUGIN-001` | Stable non-provider extension points | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-002` | Versioned manifest and compatibility range | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-003` | Deny-by-default registration/enablement | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-004` | Lifecycle state machine | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-005` | Least-privilege permissions | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-006` | Time/resource/concurrency/cancellation bounds | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-007` | Deterministic ordering/dependencies/cycle rejection | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-008` | Failure isolation/bulkheading | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-009` | Authorized audited hot disable | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-010` | Compatibility validation before activation | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-011` | Lifecycle/invocation audit records | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |
| `FR-PLUGIN-012` | Certification kit/catalog evidence | **Missing** | No platform evidence | Build-09 / Build-06 / Build-05 |

## Enterprise governance requirements

| Requirement | Required behavior | Current evidence verdict | Platform/restart status | Owner |
|---|---|---|---|---|
| `FR-ENT-001` | Enforced tenant isolation at every scoped boundary | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-002` | Signed/versioned governed policy packs | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-003` | Tamper-evident redaction-safe audit | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-004` | Deny-by-default RBAC | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-005` | Fleet diagnostics | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-006` | LTS/maintenance/vulnerability/upgrade policy | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-007` | Certified provider/plugin catalog | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-008` | Durable bounded offline audit buffer | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-009` | Data-residency enforcement | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-010` | Authorized idempotent operational overrides | **Partial retry/circuit administration only** | Not accepted until native Android, KMP Android and KMP iOS evidence exists | Build-10 / Build-06 / Build-09 |
| `FR-ENT-011` | Authorized redacted support bundles | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |
| `FR-ENT-012` | Configuration locks/signatures | **Missing** | No platform evidence | Build-10 / Build-06 / Build-09 |

## Immutable release and market evidence

| ID | Requirement | Current verdict | Owner |
|---|---|---|---|
| `REL-01` | All implementation gates #93–#99 and strategy/platform gates #101–#102 accepted | **Blocked** | Release-Audit / Build-08 / product owner |
| `REL-02` | One immutable candidate used for qualification, staging and promotion | **Missing** | Release-Audit / Build-08 / product owner |
| `REL-03` | External consumers resolve staged artifacts without project substitution | **Missing** | Release-Audit / Build-08 / product owner |
| `REL-04` | Artifact metadata, BOM, checksums and compatibility verified | **Missing** | Release-Audit / Build-08 / product owner |
| `REL-05` | SBOM, provenance, signatures, vulnerability and license evidence | **Missing** | Release-Audit / Build-08 / product owner |
| `REL-06` | Performance/resource and fault-injection limits accepted | **Missing** | Release-Audit / Build-08 / product owner |
| `REL-07` | Documentation, migration, operations, security and release notes match candidate | **Partial** | Release-Audit / Build-08 / product owner |
| `REL-08` | Legal, namespace, signing/key-custody and publication approval | **Missing** | Release-Audit / Build-08 / product owner |
| `REL-09` | Rollback/revocation rehearsal and post-publish smoke | **Missing** | Release-Audit / Build-08 / product owner |
| `MKT-01` | 20 qualified problem/customer interviews | **0 evidenced** | Market-Lead / product owner |
| `MKT-02` | 5 active design partners | **0 evidenced** | Market-Lead / product owner |
| `MKT-03` | 3 monitored production pilots | **0 evidenced** | Market-Lead / product owner |
| `MKT-04` | 1 paid pilot | **0 evidenced** | Market-Lead / product owner |

## Audit execution points

Audit-01 runs this matrix:

1. before a workstream starts;
2. before its pull request opens;
3. on the final immutable pull-request SHA;
4. after the merge reaches `main`; and
5. before any release gate or Market-readiness percentage changes.

## Required evidence fields for every accepted row

| Evidence class | Required content |
|---|---|
| Production source | Exact public/internal type and runtime dispatch path |
| Executable tests | Success, failure, cancellation, duplicate, concurrency and forbidden-call cases |
| Durability | Persistence, migration, restart, process loss and lease recovery where applicable |
| Native Android | Managed-device or host-controlled executable evidence |
| KMP Android | Explicit Android variant and staged consumer evidence |
| KMP iOS | Executable simulator/device consumer and lifecycle/background evidence |
| Security | Redaction, credential/payload isolation, permissions and abuse cases |
| Compatibility | JVM/KLib ABI, Gradle metadata, Apple headers, schema/format migration |
| Documentation | Current behavior, supported/degraded/unsupported limits and no overclaim |
| Immutable identity | Audited source SHA, workflow runs and produced artifact identity |

## Audit finding format

```text
AUDITED SHA:
PRIMARY GATE:
REQUIREMENTS REVIEWED:
IMPLEMENTED:
PARTIAL:
MISSING:
PLATFORM GAPS:
DURABILITY/RESTART GAPS:
SECURITY/REDACTION GAPS:
DOCUMENTATION DRIFT:
BLOCKERS:
VERDICT: ACCEPT | REJECT | PARTIAL
NEXT OWNERS:
```

## Current Audit-01 decision

At `241ead7a5daec4b4fe1e6c88ffc081610dad55bd`, DataLoom remains **NO-GO**. Gate `#93` is the only accepted complete gate. The audited durable cache-refresh admission checkpoint from PR `#203` and the Audit-02 checklist from PR `#205` are present, but neither completes a parent release gate. The strategy, platform, retry qualification, conflict, event, asset, plugin, enterprise and release rows above remain open. No percentage may increase from this matrix until a whole gate is accepted on one reviewed SHA.
