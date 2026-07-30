# DL-AUDIT-005 foundation and release-gate addendum

## Scope

This addendum audits the prerequisite gate owned by #93 / DL-039 and the final
release implications owned by #100 / DL-046. It supplements
`DL-AUDIT-005-current-v1-conformance.md`; it does not change the V1 **NO-GO**
decision.

## DL-039 acceptance matrix

| Foundation acceptance criterion | Verdict | Current evidence and gap |
|---|---|---|
| Approved module/artifact graph and package ownership | **Partial** | Project modules and dependency direction are documented and enforced for model, provider API, API, core, runtime, testing, three Android adapters, external consumer, and Apple assembly. PR #103 removed public `dataloom-core`/testing leakage and established ABI gates. The frozen V1 BOM, configuration API, plugin API, asset artifact, Android umbrella, iOS artifact, JVM publication, approved coordinates, and published dependency metadata do not exist. |
| Configuration snapshots, validation, precedence, rollout, rollback | **Missing** | `ConfigurationVersion` is an identifier carried in context/strategy evidence, not a versioned immutable configuration snapshot system. No repository-wide configuration precedence, signed rollout, rollback, locked-key, or compatibility engine was found. |
| Deterministic shared policy foundation | **Partial** | The synchronization strategy evaluator and retry policy contracts are deterministic bounded foundations. There is no shared policy engine used across retry reclassification, conflict precedence, content policy, plugin permissions, residency, and administrative overrides. |
| Durable transactional versioned state primitives | **Partial** | Queue state, retry budgets, circuit-state SPI, probe leases, and Android Room migrations exist. Unresolved conflicts, durable event/outbox, asset sessions, audit buffers, administrative commands, and KMP iOS persistence are absent. There is no one approved cross-subsystem transaction/versioning model. |
| Canonical operational/audit envelope and redaction boundary | **Missing** | Typed synchronization events and canonical errors exist, but no complete versioned envelope with event ID/type/source/schema version/tenant/workflow/correlation/causation/trace plus a centralized redaction/minimization service and serialization compatibility kit exists. Repository search found no production `AuditEnvelope` or `Redactor` implementation. |
| UTC plus monotonic time | **Partial** | Injected UTC epoch-millisecond `DataLoomClock`/`DataLoomInstant` and deterministic test clocks exist. No production monotonic duration abstraction or cross-clock skew model exists. |
| Deterministic and secure randomness boundaries | **Partial** | Retry jitter has an injectable deterministic random source and seeded implementation. A production secure randomness/key-generation boundary and policy for cryptographic versus non-cryptographic randomness is absent. |
| Stable identifier generation | **Partial** | Stable value types and injected identifier generation/testing support exist. Complete event/audit/asset/plugin/admin identity families and release compatibility evidence remain absent. |
| Security primitives | **Missing** | Documentation avoids secrets and several models enforce bounded/sanitized fields, but there is no shared least-privilege capability system, credential/key-reference service, signature verification, integrity framework, centralized input classification/redaction, supply-chain API, or security abuse test kit. |
| Public API/ABI baselines and external consumer fixtures | **Implemented as a foundation** | Exact JVM/Kotlin-Native ABI checks, external JVM/iOS compilation, Apple header audit, and Swift smoke checks exist for current public modules. These are source-build compatibility gates, not staged-publication or complete KMP Android/iOS consumer evidence. |
| Defaults exclude secrets and unbounded telemetry | **Partial** | Current retry/circuit contracts deliberately exclude payloads, credentials, raw headers, and arbitrary metadata. Other subsystems and the centralized enforcement/telemetry-cardinality gate do not yet exist. |
| Mandatory foundations no longer documented as deferred | **Fail** | Current documentation truthfully marks several mandatory V1 foundations and product paths as partial or missing. That is correct documentation, but it means #93 acceptance is not met. |

## Module and distribution finding

The current `settings.gradle.kts` includes these shared source-build projects:

- `dataloom-model`
- `dataloom-provider-api`
- `dataloom-api`
- `dataloom-core`
- `dataloom-runtime`
- `dataloom-testing`
- `runtime-external-consumer`

Android builds conditionally add connectivity, WorkManager scheduler, and Room
queue/circuit adapters. macOS or explicit cross-compilation adds the Apple
XCFramework assembly project.

This is a coherent source repository graph. It is not the approved published V1
artifact graph. In particular:

- no BOM or publication staging graph is present;
- no explicit KMP Android variant/consumer path exists for the shared modules;
- no production `dataloom-ios` platform artifact exists;
- the Apple module remains a distribution assembly path rather than the required
  iOS lifecycle/connectivity/background/files/security/persistence aggregation;
- current external consumers compile project/source artifacts rather than one
  immutable staged release candidate.

## Release-gate implications

The current CI lanes provide useful evidence:

- JVM/common tests and exact ABI checks;
- external JVM and Kotlin/Native consumer compilation;
- Android assemble, lint, unit, Room schema, migration, and managed-device tests;
- Kotlin/Native compilation, XCFramework slices, exported-header audit, and Swift
  smoke compilation.

They do not yet prove:

- publication metadata, repository staging, BOM resolution, checksums, signing,
  SBOM, provenance, or promotion without rebuild;
- explicit KMP Android and production KMP iOS consumers;
- complete durable-state migration/restart/fault/concurrency behavior for all
  required subsystems;
- license and namespace authority;
- security, dependency, vulnerability-response, and supply-chain approval;
- support/LTS policy, migration guide, runbooks, release notes, and immutable
  candidate approval.

## Foundation verdict

#93 remains **open and partial**. The API/ABI and module-boundary work is strong,
but the shared configuration, policy, state, event/audit, redaction, monotonic
time, secure randomness, security, publication, and mandatory platform
foundations are not complete.

Because #93 is a prerequisite for #94–#100, downstream bounded implementations
must continue to avoid claiming full V1 acceptance until the foundation contract
is completed or an explicit, approved scope change is recorded.
