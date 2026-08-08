# V1 requirements and evidence matrix

**Status:** active Audit-01 control record  
**Current audited `main`:** `ab3450c6889cf9fecc706ba1ac3e8476d25b1829`  
**Audit date:** 2026-08-08  
**Program control:** issue #188  
**Detailed audit:** [DL-AUDIT-007](./DL-AUDIT-007-end-to-end-v1-release.md)

## Acceptance rules

| Verdict | Meaning |
|---|---|
| **ACCEPTED** | Every issue criterion has production source, executable tests, required platform/restart/security/compatibility evidence and one reviewed immutable commit |
| **IMPLEMENTED / QUALIFICATION BLOCKED** | Production behavior is substantial, but mandatory executable qualification is missing |
| **PARTIAL** | A useful subset exists; the complete V1 semantic guarantee is absent |
| **MISSING** | No accepted production subsystem exists |
| **BLOCKED / NO-GO** | A prerequisite or final release condition prevents acceptance |

## Current gate matrix

| Gate | Verdict | Accepted implementation evidence | Release blockers |
|---|---|---|---|
| #93 Foundations/artifacts/compatibility | **PARTIAL / REOPENED** | Current public/provider/runtime boundaries, existing module rules, JVM/KLib ABI baselines, source-build consumers, queue/retry/circuit state, canonical envelope/redaction/wire/upcast | Final artifact graph/BOM/publication, config snapshots and rollback, shared policy, complete durable state, monotonic time, secure RNG/key/signature/integrity, explicit KMP Android, platform aggregates, staged consumers |
| #94 Retry/circuit | **IMPLEMENTED / QUALIFICATION BLOCKED** | FR-RETRY-001–012 common/runtime foundation | Real Android/Apple process loss, true cross-process contention where supported, complete AC-FUNC-004 through all three staged consumers |
| #95 Conflict | **PARTIAL** | Custom detector/resolver contracts and orchestration | Built-ins, apply, persistence, audit, precedence, convergence/quarantine, retry/event/platform proof |
| #96 Events/observability/operations | **PARTIAL** | Canonical envelope/redaction/wire/upcast, in-process dispatcher, retry/circuit telemetry | Durable outbox/order/ack/replay/retention/filtering, platform persistence, SDK-wide instrumentation, operations read model/adaptor |
| #97 Assets | **MISSING** | None | FR-ASSET-001–012 |
| #98 Plugins | **MISSING** | Provider SPI only | FR-PLUGIN-001–012 |
| #99 Enterprise | **MISSING** | Tenant identifiers and limited administration | FR-ENT-001–012 |
| #100 Immutable V1 release | **BLOCKED / NO-GO** | Current-module CI foundations | All gates; publication/staging; license; SBOM/provenance/signatures; benchmarks; legal/security/support; rollback and exact-candidate promotion |
| #101 Platform parity | **PARTIAL / REOPENED** | Native Android adapters; Apple targets/stores/XCFramework compile evidence | Explicit KMP Android, production `dataloom-ios`, lifecycle/connectivity/BGTask/security/files, real process relaunch, staged apps, complete parity matrix |
| #102 Six strategies | **PARTIAL** | Six-strategy planner plus bounded network-only, remote-first, offline-first and cache-first slices | Online offline-first, hybrid runtime, full adaptive/cache matrices, conflicts/events/restart/platform qualification |

> **Formal V1 gate completion: 0 of 10 (0%).**

## Strategy matrix

| Strategy | Production execution on `main` | Durability/restart | Mandatory platforms | Verdict |
|---|---|---|---|---|
| Offline-first | Deferred atomic admission only | Decision/plan persisted; online ownership and real process recovery missing | No complete shared contract-kit evidence | **PARTIAL** |
| Remote-first | Direct PUSH/PULL/BIDIRECTIONAL and typed PULL fallback | Durable defer/restart incomplete | No complete three-platform matrix | **PARTIAL** |
| Cache-first | Direct serve, remote directions, inline PULL refresh and durable admission | Callback/relaunch, BIDIRECTIONAL refresh and coherence incomplete | No complete three-platform matrix | **PARTIAL** |
| Network-only | Direct transport-only PUSH/PULL/BIDIRECTIONAL | Not applicable for local durability; failure/event qualification incomplete | No complete three-platform matrix | **PARTIAL** |
| Hybrid | Planner only | Missing | Missing | **MISSING RUNTIME** |
| Adaptive | Deterministic selection to approved concrete plan | Selected plan persists; full authorized re-evaluation and concrete execution incomplete | Missing | **PARTIAL** |

## Platform matrix

| Requirement | Native Android | KMP Android | KMP iOS |
|---|---|---|---|
| Explicit published variant | Android libraries exist source-side | **Missing explicit Android KMP target** | Apple producer variants exist; production aggregate missing |
| Platform aggregate | **Missing `dataloom-android`** | Same | **Missing `dataloom-ios`** |
| Connectivity | Android provider exists | Consumer evidence missing | Production Apple provider missing |
| Background scheduling | WorkManager scheduler exists; exact callback identity/relaunch incomplete on main | Missing staged consumer | BGTaskScheduler integration missing |
| Durable queue/retry/circuit | Room foundation exists | Same code not qualified as KMP Android consumer | Apple file foundations exist |
| Real process/host relaunch | Missing on accepted main | Missing | Missing |
| Conflict persistence | Missing | Missing | Missing |
| Durable event outbox | Missing | Missing | Missing |
| Asset sessions/files/security | Missing general subsystem | Missing | Missing production aggregation |
| Staged executable reference app | 0 | 0 | 0 |
| Overall | **PARTIAL** | **MISSING mandatory path** | **PARTIAL** |

## FR-RETRY-001–012

| ID | Requirement | Current verdict |
|---|---|---|
| FR-RETRY-001 | Central failure classification | Implemented; platform closure pending |
| FR-RETRY-002 | Immediate/fixed/linear/exponential and custom policy | Implemented |
| FR-RETRY-003 | Deterministic jitter with injected randomness | Implemented |
| FR-RETRY-004 | Attempt/elapsed/cumulative budgets | Implemented; process-loss proof pending |
| FR-RETRY-005 | Bounded provider/server hints | Implemented; adapter proof pending |
| FR-RETRY-006 | Connection/request/idle/workflow/provider/policy timeouts | Implemented; complete failure matrix pending |
| FR-RETRY-007 | Durable CLOSED/OPEN/HALF_OPEN circuit | Implemented; mandatory-path proof pending |
| FR-RETRY-008 | Controlled durable half-open probe lease | Implemented; true cross-process proof pending |
| FR-RETRY-009 | Durable retry/circuit/next-time state | Implemented; OS termination/relaunch pending |
| FR-RETRY-010 | Bounded redacted observability | Implemented for retry/circuit scope |
| FR-RETRY-011 | Authorized idempotent manual retry/requeue | Implemented; process-loss proof pending |
| FR-RETRY-012 | Fail-closed non-retryable protection/reclassification | Implemented; platform proof pending |

## FR-CONFLICT-001–012

| ID | Requirement | Current verdict |
|---|---|---|
| FR-CONFLICT-001 | Standard detection utilities | Partial |
| FR-CONFLICT-002 | Built-in client/server/LWW/timestamp/field/reject/manual policies | Missing |
| FR-CONFLICT-003 | Custom resolver with safe context | Partial |
| FR-CONFLICT-004 | Resolved/unresolved/retry/reject/user-action outcomes | Partial |
| FR-CONFLICT-005 | Determinism validation/certification | Missing |
| FR-CONFLICT-006 | Immutable conflict audit record | Missing |
| FR-CONFLICT-007 | Durable unresolved/manual state | Missing |
| FR-CONFLICT-008 | Fingerprint/convergence/quarantine/loop prevention | Missing |
| FR-CONFLICT-009 | Entity > workflow > tenant > global precedence | Missing |
| FR-CONFLICT-010 | Sensitive-field minimization/redaction | Partial foundation |
| FR-CONFLICT-011 | Bounded conflict metrics | Missing |
| FR-CONFLICT-012 | Atomic detect/resolve/apply/checkpoint/audit/event boundary | Missing |

## FR-EVENT-001–012 and NFR-OBS-001–012

| ID | Requirement | Current verdict |
|---|---|---|
| FR-EVENT-001 | Versioned canonical envelope | Implemented foundation |
| FR-EVENT-002 | Complete event categories | Partial |
| FR-EVENT-003 | Authoritative per-workflow ordering | Missing |
| FR-EVENT-004 | Optional durable at-least-once delivery | Missing |
| FR-EVENT-005 | Acknowledgement and replay | Missing |
| FR-EVENT-006 | Retention and exact expiry | Missing |
| FR-EVENT-007 | Subscription filtering | Missing |
| FR-EVENT-008 | Bounded buffers/back-pressure/overflow | Partial retry/circuit-only |
| FR-EVENT-009 | Central redaction/minimization | Implemented foundation |
| FR-EVENT-010 | Notification hooks/schema evolution/upcast | Partial |
| FR-EVENT-011 | Persisted operational/audit events and isolation | Missing |
| FR-EVENT-012 | SDK-owned operational query/read model | Missing |
| NFR-OBS-001 | Structured logging | Partial |
| NFR-OBS-002 | Bounded-cardinality metrics | Partial |
| NFR-OBS-003 | Tracing/correlation propagation | Partial |
| NFR-OBS-004 | Exporter/adaptor isolation | Partial |
| NFR-OBS-005 | Sampling controls | Missing |
| NFR-OBS-006 | Telemetry failure isolation | Partial |
| NFR-OBS-007 | Health/degradation model | Partial |
| NFR-OBS-008 | Stable diagnostic reason codes | Partial |
| NFR-OBS-009 | Monotonic duration measurement | Missing complete foundation |
| NFR-OBS-010 | Redacted support snapshots | Partial retry/circuit scope |
| NFR-OBS-011 | Operations dashboard/adaptor | Missing |
| NFR-OBS-012 | SDK-wide instrumentation coverage | Missing |

## FR-ASSET-001–012

| ID | Requirement | Verdict |
|---|---|---|
| FR-ASSET-001 | Versioned manifest | Missing |
| FR-ASSET-002 | Chunked upload | Missing |
| FR-ASSET-003 | Chunked download | Missing |
| FR-ASSET-004 | Durable resumable sessions | Missing |
| FR-ASSET-005 | Per-chunk/whole-object integrity | Missing |
| FR-ASSET-006 | Bounded-memory streaming | Missing |
| FR-ASSET-007 | Parallelism/fairness | Missing |
| FR-ASSET-008 | Compression negotiation | Missing |
| FR-ASSET-009 | Encryption metadata without keys | Missing |
| FR-ASSET-010 | Private temporary files/atomic promotion | Missing |
| FR-ASSET-011 | Quota/cancellation/cleanup | Missing |
| FR-ASSET-012 | Content policy/scan/quarantine hooks | Missing |

## FR-PLUGIN-001–012

| ID | Requirement | Verdict |
|---|---|---|
| FR-PLUGIN-001 | Stable non-provider extension points | Missing |
| FR-PLUGIN-002 | Versioned manifest/compatibility | Missing |
| FR-PLUGIN-003 | Deny-by-default registration/enablement | Missing |
| FR-PLUGIN-004 | Lifecycle state machine | Missing |
| FR-PLUGIN-005 | Least-privilege permissions | Missing |
| FR-PLUGIN-006 | Resource/time/concurrency/cancellation bounds | Missing |
| FR-PLUGIN-007 | Ordering/dependencies/cycle rejection | Missing |
| FR-PLUGIN-008 | Failure isolation/bulkheading | Missing |
| FR-PLUGIN-009 | Authorized audited hot disable | Missing |
| FR-PLUGIN-010 | Pre-activation compatibility validation | Missing |
| FR-PLUGIN-011 | Lifecycle/invocation audit | Missing |
| FR-PLUGIN-012 | Certification kit/catalog | Missing |

## FR-ENT-001–012

| ID | Requirement | Verdict |
|---|---|---|
| FR-ENT-001 | Enforced tenant isolation | Missing |
| FR-ENT-002 | Signed/versioned policy packs | Missing |
| FR-ENT-003 | Tamper-evident redaction-safe audit | Missing |
| FR-ENT-004 | Deny-by-default RBAC | Missing |
| FR-ENT-005 | Fleet diagnostics | Missing |
| FR-ENT-006 | LTS/maintenance/vulnerability/upgrade policy | Missing |
| FR-ENT-007 | Certified provider/plugin catalog | Missing |
| FR-ENT-008 | Durable bounded offline audit buffer | Missing |
| FR-ENT-009 | Data residency enforcement | Missing |
| FR-ENT-010 | Authorized idempotent overrides | Partial retry/circuit-only |
| FR-ENT-011 | Authorized redacted support bundles | Missing |
| FR-ENT-012 | Configuration locks/signatures | Missing |

## Foundation-specific acceptance

| #93 criterion | Current verdict |
|---|---|
| Approved published artifact graph and Gradle enforcement | Partial |
| Configuration snapshot/precedence/rollout/rollback | Missing |
| Shared deterministic policy foundation | Partial subsystem-specific only |
| Durable transactional/versioned state across V1 | Partial queue/retry/circuit only |
| Canonical envelope/redaction/wire | Implemented foundation |
| UTC plus monotonic time | Partial wall-clock only |
| Deterministic plus secure randomness | Partial deterministic jitter only |
| Stable identifier generation | Partial |
| Least privilege/integrity/signature/key/supply-chain primitives | Missing/partial |
| API/ABI and source-build consumer gates | Implemented foundation |
| Defaults exclude secrets/unbounded values | Partial |
| Mandatory foundations no longer deferred | Fail |

## Release and market evidence

| ID | Requirement | Verdict |
|---|---|---|
| REL-01 | All implementation/platform gates accepted | Blocked |
| REL-02 | One immutable qualification/staging/promotion candidate | Missing |
| REL-03 | Staged external consumers without substitution | Missing |
| REL-04 | BOM/POM/module/checksums/compatibility | Missing |
| REL-05 | SBOM/provenance/signatures/vulnerability/license evidence | Missing |
| REL-06 | Performance/resource/fault limits | Missing |
| REL-07 | Candidate-matching docs/migration/operations/security/release notes | Partial |
| REL-08 | Legal/namespace/signing/publication approval | Missing |
| REL-09 | Rollback/revocation and post-publish smoke | Missing |
| MKT-01 | 20 interviews | 0 evidenced |
| MKT-02 | 5 design partners | 0 evidenced |
| MKT-03 | 3 production pilots | 0 evidenced |
| MKT-04 | 1 paid pilot | 0 evidenced |

## Dependency/security exceptions

- No accepted recent production change adds a vendor SDK, SaaS, database wrapper,
  networking wrapper or analytics integration.
- Production uses Kotlin/coroutines and official Android/AndroidX components.
- `org.mockito.kotlin:mockito-kotlin` exists in Android tests. A literal
  zero-third-party policy requires removal/replacement or explicit test-only
  approval.
- License, dependency verification, SBOM, provenance, signatures and complete
  supply-chain approval are missing.

## Current Audit-01 decision

At `ab3450c6889cf9fecc706ba1ac3e8476d25b1829`, DataLoom is **NO-GO**. No full V1 gate is accepted.
Substantial bounded implementations are retained as partial evidence, but no
percentage may exceed `0%` until one complete gate satisfies its full issue
criteria on one reviewed immutable commit.
