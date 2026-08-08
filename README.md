# DataLoom

**Synchronize once. Scale everywhere.**

DataLoom is an Android-first, Kotlin Multiplatform synchronization SDK for
native Android and KMP applications targeting Android and iOS. Its frozen V1
product scope contains six built-in strategies:

| Strategy | Product intent |
|---|---|
| Offline-first | Commit eligible local intent durably, then reconcile |
| Remote-first | Attempt the remote authority first with explicit typed fallback |
| Cache-first | Serve application-owned synchronized state under explicit freshness and refresh rules |
| Network-only | Use transport only, with zero storage or queue side effects |
| Hybrid | Compose explicit primary, fallback, persistence, and coherence rules |
| Adaptive | Deterministically select and freeze one approved concrete strategy |

Strategy is independent from transfer direction (`PUSH`, `PULL`,
`BIDIRECTIONAL`), transfer mode (`FULL`, `DELTA`), and execution trigger.

> [!WARNING]
> DataLoom is in active pre-V1 development. The current source tree contains
> substantial buildable foundations and several correct bounded runtime slices,
> but **no complete V1 release gate is currently accepted**. Do not present the
> repository or its artifacts as production-ready V1.

## End-to-end V1 audit status

- **Last reconciled:** 2026-08-08
- **Audited `main`:** `ab3450c6889cf9fecc706ba1ac3e8476d25b1829`
- **Recorded target:** 2026-08-27
- **Current verdict:** **NO-GO**
- **Formal V1 gate completion:** **0%** (`0 of 10` gates accepted)
- **Fully qualified strategies:** `0 of 6`
- **Staged reference applications:** `0 of 3`
- **Immutable release candidate:** none

The gate score is acceptance-based, not a percentage of code written. A gate is
accepted only when every requirement has production source, executable tests,
mandatory platform evidence, durability/restart evidence where applicable,
security and compatibility evidence, truthful documentation, and one reviewed
immutable commit.

The 2026-08-08 audit reopened gates
[#93](https://github.com/dataloom-sdk/dataloom/issues/93) and
[#101](https://github.com/dataloom-sdk/dataloom/issues/101), which had been
closed before their own acceptance criteria were satisfied.

## V1 release-gate dashboard

| Priority | Gate | Current verdict | What is implemented | What still blocks acceptance |
|---:|---|---|---|---|
| 1 | [#93 Foundations, artifacts, compatibility](https://github.com/dataloom-sdk/dataloom/issues/93) | **PARTIAL / REOPENED** | Public/provider/runtime boundaries, current module dependency rules, JVM and Kotlin/Native ABI baselines, source-build external-consumer compilation, durable queue/retry/circuit primitives, canonical operational envelope, centralized redaction, deterministic wire codec and upcasting | Approved published artifact graph and BOM; immutable configuration snapshots, precedence, rollout and rollback; shared policy engine; complete cross-subsystem durable state; monotonic time; secure-random/key/signature/integrity boundaries; explicit KMP Android variant; production Android/iOS/JVM aggregates; staged consumers; publication and supply-chain foundations |
| 2 | [#96 Events, observability, operations](https://github.com/dataloom-sdk/dataloom/issues/96) | **PARTIAL** | Canonical envelope/redaction/wire/upcast; synchronous in-process observer delivery; bounded retry/circuit telemetry and health foundations | Durable event store/outbox, sequence ordering, acknowledgement/replay, retention, filtering, back-pressure, platform persistence, SDK-wide instrumentation, operations read model and deployable reference adaptor |
| 3 | [#94 Retry and circuit breaker](https://github.com/dataloom-sdk/dataloom/issues/94) | **IMPLEMENTED / QUALIFICATION BLOCKED** | Central failure protection, standard backoff and deterministic jitter, retry budgets, hints, separated timeouts, durable circuit state, half-open probe leases, Room/Apple state, administration and bounded telemetry | Real Android and Apple process termination/relaunch, true cross-process probe contention where supported, and complete AC-FUNC-004 through native Android, explicit KMP Android and KMP iOS staged consumers |
| 4 | [#101 Platform parity](https://github.com/dataloom-sdk/dataloom/issues/101) | **PARTIAL / REOPENED** | Native Android connectivity/Room/WorkManager foundations; Apple targets and file-backed queue/retry/circuit stores; XCFramework/header/Swift compile checks | Explicit KMP Android variants, `dataloom-android`, production `dataloom-ios`, iOS lifecycle/connectivity/BGTask/security/files, real process relaunch, staged native Android/KMP Android/KMP iOS consumers, and complete parity matrices |
| 5 | [#102 Six-strategy engine](https://github.com/dataloom-sdk/dataloom/issues/102) | **PARTIAL** | Versioned profiles and deterministic planner for all six; direct network-only and remote-first; deferred offline-first atomic admission; direct cache serving, remote matrix, inline PULL refresh and durable refresh admission; persisted accepted plans and supported frozen-plan replay | Online offline-first ownership; hybrid executor; remaining adaptive paths; BIDIRECTIONAL refresh; conflict/event/coherence integration; process-loss recovery; complete direction/mode/trigger/failure/restart/platform matrices |
| 6 | [#95 Conflict engine](https://github.com/dataloom-sdk/dataloom/issues/95) | **PARTIAL** | Application-supplied detector/resolver contracts, registries and one-cycle orchestration | Built-in client/server/LWW/timestamp/reject/manual policies, deterministic precedence, atomic decision application, durable unresolved state, audit, retry delegation, convergence limits, quarantine, restart/concurrency/platform proof |
| 7 | [#97 Asset synchronization](https://github.com/dataloom-sdk/dataloom/issues/97) | **MISSING** | No accepted production subsystem | Complete manifest, bounded streaming, chunk upload/download, durable resume, integrity, encryption metadata, quota, cancellation, secure temporary files, cleanup, policy hooks and platform evidence |
| 8 | [#98 Plugin platform](https://github.com/dataloom-sdk/dataloom/issues/98) | **MISSING** | Provider SPI only; it is not the V1 plugin platform | Manifest, compatibility, deny-by-default permissions, lifecycle, deterministic ordering, resource bounds, isolation, hot disable, audit, certification kit and reference non-provider plugin |
| 9 | [#99 Enterprise governance](https://github.com/dataloom-sdk/dataloom/issues/99) | **MISSING** | Tenant identifiers and limited retry/circuit administration foundations | Enforced tenant isolation, RBAC, signed policy packs, tamper-evident/offline audit, residency, fleet diagnostics, support bundles, configuration locks, LTS/catalog governance and platform proof |
| 10 | [#100 Immutable V1 release](https://github.com/dataloom-sdk/dataloom/issues/100) | **BLOCKED / NO-GO** | Continuous shared, Android and Apple validation for current modules; ABI, Room schema, XCFramework and header checks | All other gates; staged publication; BOM/POM/module metadata; license; checksums/signatures; SBOM/provenance; vulnerability/license evidence; benchmarks; security/legal approvals; rollback rehearsal; exact-candidate promotion and post-publish smoke |

## Strategy implementation status

| Strategy | Current executable behavior | V1 verdict |
|---|---|---|
| Offline-first | Atomic deferred local-intent/outbox admission and frozen durable continuation persistence | **Partial:** online immediate ownership, process recovery and complete platform matrix missing |
| Remote-first | Direct provider-backed PUSH, PULL and BIDIRECTIONAL plus typed configured PULL fallback | **Partial:** durable defer/restart, complete retry/conflict/event and platform matrices missing |
| Cache-first | Provider-verified fresh/stale serving, cache miss, direct remote directions, inline PULL refresh and queue-before-scheduler durable admission | **Partial:** callback/relaunch recovery, independent protected queue/scheduler adapters, BIDIRECTIONAL refresh, coherence/conflict/events and platform matrices missing |
| Network-only | Direct transport-only PUSH, PULL and BIDIRECTIONAL with no storage/queue calls | **Partial:** complete result/event/retry/failure/platform qualification missing |
| Hybrid | Versioned profile and deterministic finite plan evaluation | **Missing runtime:** no production direct hybrid executor or coherence/reconciliation engine |
| Adaptive | Deterministic bounded selection and persistence of a concrete selected plan | **Partial:** execution is limited to currently supported concrete paths; full re-evaluation and platform matrices missing |

## What is correctly implemented today

The following are real, useful, reviewed foundations on `main`:

- immutable identifiers, requests, results, errors, provider contracts and lifecycle;
- storage, transport, connectivity, scheduler and queue SPIs;
- outbound PUSH, inbound PULL and BIDIRECTIONAL pipelines;
- durable queue entries, leases, retry budgets, workflow deadlines and accepted plans;
- in-memory, Android Room and Apple file-backed queue/circuit foundations;
- queue processing, lease recovery and accepted-plan replay for supported plans;
- standard retry strategies, deterministic jitter, budgets, hints, timeouts,
  durable circuit breaker, probe lease and administration foundations;
- Android connectivity, WorkManager scheduling and Room persistence adapters;
- Apple Kotlin/Native targets, file-backed queue/retry/circuit stores and
  XCFramework assembly;
- canonical operational envelope, centralized redaction, deterministic bounded
  wire format and schema upcasting;
- JVM and Kotlin/Native ABI baselines, external source-build consumer
  compilation, Android assembly/lint/schema/device validation, Apple target,
  XCFramework, header and Swift compile validation;
- audited cache-first durable admission merged through PR #203.

A green build proves these present modules compile and pass their current tests.
It does **not** prove absent V1 modules, staged consumers, production platform
lifecycle paths, market evidence, or publication controls.

## Platform status

| Consumer path | Current evidence | V1 status |
|---|---|---|
| Native Android | ConnectivityManager, Room and WorkManager source-build adapters; managed-device Room tests | **Partial:** no complete external staged app or full process/recovery matrix |
| KMP Android | Shared JVM variant only | **Missing mandatory evidence:** no explicit Android KMP variant or staged KMP Android app |
| KMP iOS | Apple producer targets, file stores and library-level consumer probes | **Partial:** no production `dataloom-ios`, BGTask/lifecycle/connectivity/security aggregation or executable staged KMP iOS app |
| Native Swift/XCFramework | Device/simulator slices, header audit and selected-symbol compile smoke | Optional distribution baseline only; not KMP iOS parity |

## Current repository modules

- `dataloom-model`
- `dataloom-provider-api`
- `dataloom-api`
- `dataloom-core`
- `dataloom-runtime`
- `dataloom-testing`
- `runtime-external-consumer`
- conditional Android modules:
  `dataloom-connectivity-android`, `dataloom-scheduler-workmanager`,
  `dataloom-queue-room`
- macOS/cross-compilation Apple assembly: `dataloom-apple`

The accepted V1 architecture also requires consumer/publication boundaries that
do not yet exist, including the BOM, configuration API, plugin API, assets,
Android/iOS/JVM platform aggregates and benchmark module.

## Validation

Shared:

```bash
./gradlew \
  :build-logic:test \
  build \
  :runtime-external-consumer:checkRuntimeExternalConsumer \
  --configuration-cache \
  --stacktrace \
  --console=plain
```

Android:

```bash
DATALOOM_ANDROID_BUILD=true ./gradlew \
  :dataloom-connectivity-android:assembleDebug \
  :dataloom-connectivity-android:assembleRelease \
  :dataloom-connectivity-android:testDebugUnitTest \
  :dataloom-connectivity-android:lintDebug \
  :dataloom-scheduler-workmanager:assembleDebug \
  :dataloom-scheduler-workmanager:assembleRelease \
  :dataloom-scheduler-workmanager:testDebugUnitTest \
  :dataloom-scheduler-workmanager:lintDebug \
  :dataloom-queue-room:assembleDebug \
  :dataloom-queue-room:assembleRelease \
  :dataloom-queue-room:testDebugUnitTest \
  :dataloom-queue-room:lintDebug
```

Apple validation requires macOS/Xcode and includes shared tests, all iOS target
consumer compilation, XCFramework assembly, exported-header auditing and Swift
compile smoke.

## Dependency boundary

DataLoom must remain provider-neutral and application-domain-neutral.
Applications own repositories, UI state, server contracts, authentication,
credentials, authorization inputs and business truth.

No recent accepted change added a production vendor SDK, SaaS, networking
wrapper, database wrapper or analytics integration. Current production code
uses Kotlin/coroutines and official Android/AndroidX platform components.

The repository is not literally dependency-free: Android tests currently use
`org.mockito.kotlin:mockito-kotlin`. Under a strict “no third-party dependency
at all” policy, that test-only dependency must be removed/replaced or explicitly
approved. It is not packaged into the runtime SDK.

## Immediate execution order

1. Complete #93 foundations: configuration/policy/time/security and final
   artifact/publication boundaries.
2. Rebuild and merge the #96 durable event/outbox foundation from complete
   committed source.
3. Add the #94 provider-neutral process-loss/cross-process qualification kit.
4. Rebase and qualify the salvageable #192 Android process/relaunch work.
5. Implement #193 production KMP iOS lifecycle/connectivity/BGTask/relaunch.
6. Complete #102 online offline-first, hybrid, adaptive and remaining cache
   matrices.
7. Implement #95 built-in durable conflict engine.
8. Build #197 staged native Android, KMP Android and KMP iOS consumers.
9. Implement #97 assets, #98 plugins and #99 enterprise governance.
10. Qualify and promote one immutable candidate through #100.

Only one production PR should be active until first-pass CI reliability is
consistently high. CI confirms complete committed source; it must not construct
or debug missing implementation files.

## Documentation

- [End-to-end V1 release audit](./docs/audits/DL-AUDIT-007-end-to-end-v1-release.md)
- [Live V1 requirements/evidence matrix](./docs/audits/V1-REQUIREMENTS-EVIDENCE-MATRIX.md)
- [Audit index](./docs/audits/README.md)
- [System architecture](./docs/architecture/README.md)
- [Six-strategy guide](./docs/strategies/README.md)
- [Android integration](./docs/android/README.md)
- [Apple/KMP iOS integration](./docs/apple/README.md)
- [Local build guide](./docs/development/building.md)
- [Security policy](./SECURITY.md)

## License and publication

License status is **not finalized** and no production V1 artifacts are
published. Namespace authority, signing/key custody, SBOM, provenance,
vulnerability/license evidence, support policy and legal approval remain
release blockers.
