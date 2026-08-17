# DataLoom

**Synchronize once. Scale everywhere.**

DataLoom is an Android-first, Jetpack-style synchronization SDK for native
Android and Kotlin Multiplatform applications targeting Android and iOS. Its
main purpose is to provide one deterministic engine with six built-in
synchronization strategies:

| Strategy | Intent |
|---|---|
| Offline-first | Commit local intent durably, then synchronize |
| Remote-first | Prefer the remote authority with explicit safe fallback |
| Cache-first | Serve a valid cache and refresh by policy |
| Network-only | Use the remote source with no storage or queue side effects |
| Hybrid | Compose explicit local, remote, cache, and reconciliation rules |
| Adaptive | Select an allowed concrete strategy from recorded evidence |

All six are mandatory V1 capabilities. Strategy is independent from transfer
direction (`PUSH`, `PULL`, `BIDIRECTIONAL`), transfer mode (`FULL`, `DELTA`),
and trigger.

> [!WARNING]
> DataLoom is in active pre-V1 development and is not production-ready.
> Current foundations are substantial, but the six-strategy engine and several
> mandatory V1 systems are incomplete. No release should be represented as V1
> until the documented release gates have evidence.

## Product at a glance

```mermaid
flowchart LR
    app[Application]
    request[/Synchronization request/]
    strategy{V1 strategy policy}
    plan[Deterministic plan]
    runtime[Shared runtime]
    local[(Local data)]
    remote[(Remote service)]
    durable[(Queue and recovery)]
    signals[Events and observability]

    app --> request
    request --> strategy
    strategy --> plan
    plan --> runtime
    runtime --> local
    runtime --> remote
    runtime --> durable
    runtime --> signals
    signals --> app

    style strategy fill:#FFECBD,stroke:#FFC943
    style runtime fill:#C2E5FF,stroke:#3DADFF
    style durable fill:#DCCCFF,stroke:#874FFF
```

The diagram is the approved V1 product model. The current repository implements
the shared runtime foundation, provider contracts, push/pull/bidirectional
pipelines, durable queue processing, Android adapters, Apple compilation paths,
and the versioned six-strategy contract plus deterministic built-in planner.
Plan-aware provider resolution and direct network-only/remote-first operation
execution are implemented. Bounded strategy decisions and complete immutable
accepted plans survive the in-memory, Android Room, and Apple queues. Direct,
provider-protected, and queued replay execute the frozen continuation without
current-policy evaluation. All six built-in strategies now execute correctly
end to end, and opt-in conflict detection during inbound pull is reachable
from the public builder for all of them. Atomic application outbox semantics,
durable strategy events, and platform reference qualification remain before
the strategy engine is complete.

## Market-readiness dashboard

- **Last reconciled:** 2026-08-16
- **Recorded V1 target:** 2026-08-27
- **Current verdict:** **NO-GO — not production-ready or market-ready**
- **Accepted engineering/release gates:** **0 of 10** (`#93`–`#102` all open)

Progress is acceptance-based, not a percentage of code written: a gate is
`COMPLETE` only when its issue criteria have executable evidence on the same
reviewed commit, so a gate can absorb real, tested, merged work for a long
time before it flips.

| # | V1 gate | Status | Est. completion |
|---:|---|---|---:|
| 0 | [DL-039 foundations, artifacts, compatibility](https://github.com/dataloom-sdk/dataloom/issues/93) | PARTIAL | 75% |
| 1 | [DL-039B six strategy engine](https://github.com/dataloom-sdk/dataloom/issues/102) | IN PROGRESS | 75% |
| 2 | [DL-039A Android/KMP/iOS parity](https://github.com/dataloom-sdk/dataloom/issues/101) | IN PROGRESS | 65% |
| 3 | [DL-040 retry and circuit breaker](https://github.com/dataloom-sdk/dataloom/issues/94) | QUALIFICATION BLOCKED | 70% |
| 4 | [DL-041 conflict engine](https://github.com/dataloom-sdk/dataloom/issues/95) | IN PROGRESS | 35% |
| 5 | [DL-042 events, observability, health, dashboard](https://github.com/dataloom-sdk/dataloom/issues/96) | IN PROGRESS | 40% |
| 6 | [DL-043 asset synchronization](https://github.com/dataloom-sdk/dataloom/issues/97) | NOT STARTED | 0% |
| 7 | [DL-044 plugin platform](https://github.com/dataloom-sdk/dataloom/issues/98) | NOT STARTED | 15% |
| 8 | [DL-045 enterprise governance](https://github.com/dataloom-sdk/dataloom/issues/99) | NOT STARTED | 10% |
| 9 | [DL-046 immutable V1 release](https://github.com/dataloom-sdk/dataloom/issues/100) | BLOCKED / NO-GO | 10% |

*Est. completion is a rough, human-judged progress signal (see the [full dashboard](./docs/status/market-readiness.md#full-v1-gate-table) for methodology) — the `Status` column remains authoritative for gate acceptance.*

**Recent highlights** (newest first):

- 2026-08-16 — `SqlDelightStorageProvider` adopts `readLocalConflictCandidate`, the second reference provider to do so, after Room (`#311`)
- 2026-08-16 — `RoomStorageProvider` adopts `readLocalConflictCandidate`, the first reference provider to do so (`#309`)
- 2026-08-16 — `AppleFileDurableStateStore`, the second platform implementation of the durable-state contract, alongside `RoomDurableStateStore` (`#307`)
- 2026-08-16 — Real Gradle Managed Device AVD emulator proof for the Android reference consumer, one tier more real than Robolectric (`#304`)
- 2026-08-16 — `DataLoom.synchronize()` genuinely writes a pulled change to real storage, proven on both Android and iOS (`#302`, `#303`)

The full dated log (every entry back to project start), per-gate evidence
citations, adoption-readiness table, market-evidence gates, and execution
order live in the
**[full market-readiness dashboard](./docs/status/market-readiness.md)**.

## Supported V1 consumer paths

| Consumer | V1 status |
|---|---|
| Native Android application | Mandatory |
| KMP application targeting Android | Mandatory |
| KMP application targeting iOS | Mandatory |
| Native Swift application through an XCFramework | Optional distribution path |

A native Android application remains Android-only. iOS becomes part of the
same product codebase when the application itself is Kotlin Multiplatform and
declares an iOS target. See
[platform strategy](./docs/architecture/platform-strategy.md).

## Repository modules

```mermaid
flowchart TD
    model[dataloom-model]
    api[dataloom-api]
    core[dataloom-core]
    runtime[dataloom-runtime]
    testing[dataloom-testing]
    ktor[dataloom-transport-ktor]
    connectivity[dataloom-connectivity-android]
    room[dataloom-queue-room]
    storageRoom[dataloom-storage-room]
    work[dataloom-scheduler-workmanager]
    apple[dataloom-apple]

    model --> api
    model --> core
    api --> core
    model --> runtime
    api --> runtime
    core --> runtime
    model --> testing
    api --> testing
    core --> testing
    runtime --> testing
    ktor --> api
    api --> connectivity
    api --> room
    api --> storageRoom
    model --> storageRoom
    model --> room
    api --> work
    runtime --> work
    model --> apple
    api --> apple
    core --> apple
    runtime --> apple

    style model fill:#DCCCFF,stroke:#874FFF
    style api fill:#C2E5FF,stroke:#3DADFF
    style runtime fill:#C6FAF6,stroke:#5AD8CC
```

| Module | Responsibility |
|---|---|
| `dataloom-model` | Dependency-root models; currently owns clock primitives |
| `dataloom-api` | Public contracts, requests, results, and provider interfaces |
| `dataloom-core` | Provider lifecycle, registry, resolution, and shared runtime dependencies |
| `dataloom-runtime` | Facade, pipelines, queue, retry, conflict, and event orchestration |
| `dataloom-testing` | Deterministic clocks, in-memory providers, scripts, and recorders |
| `dataloom-transport-ktor` | Optional Ktor-backed reference `TransportProvider` |
| `dataloom-connectivity-android` | Android `ConnectivityProvider` |
| `dataloom-queue-room` | Room-backed durable `QueueProvider` |
| `dataloom-storage-room` | Room-backed reference `StorageProvider` |
| `dataloom-scheduler-workmanager` | WorkManager scheduler and worker bridge |
| `dataloom-apple` | macOS-only Apple/XCFramework umbrella |

The exact dependency and publication rules are in
[module architecture](./docs/architecture/modules.md) and
[ADR-0002](./docs/adr/ADR-0002-v1-artifact-and-foundation-architecture.md).

## Toolchain

| Tool | Version |
|---|---|
| JDK | 17 or newer |
| Gradle Wrapper | 9.5.0 |
| Kotlin | 2.4.10 |
| JVM bytecode target | 17 |
| Shared targets | JVM, `iosArm64`, `iosSimulatorArm64`, `iosX64` |

An explicit KMP Android target and complete KMP consumer qualification remain
V1 gates.

## Build the current shared foundation

Use the checked-in Gradle Wrapper:

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

Android modules are included only when `DATALOOM_ANDROID_BUILD=true` and require
an Android SDK plus access to Google Maven:

```bash
DATALOOM_ANDROID_BUILD=true ./gradlew \
  :dataloom-connectivity-android:build \
  :dataloom-queue-room:build \
  :dataloom-scheduler-workmanager:build
```

Apple compilation, simulator tests, and XCFramework assembly require macOS and
Xcode:

```bash
./gradlew :dataloom-apple:assembleDataLoomReleaseXCFramework
```

Output:
`dataloom-apple/build/XCFrameworks/release/DataLoom.xcframework`

For host requirements, offline-cache limitations, and the lowest-cost local
validation order, read [building DataLoom](./docs/development/building.md).

## Documentation

Start with the [documentation hub](./docs/README.md).

- [Getting started quickstart](./docs/getting-started.md)
- [System overview](./docs/architecture/system-overview.md)
- [Six-strategy guide](./docs/strategies/README.md)
- [API reference](./docs/api/README.md)
- [Architecture](./docs/architecture/README.md)
- [Android integration](./docs/android/README.md)
- [KMP iOS and Apple integration](./docs/apple/README.md)
- [Testing](./docs/testing/testing-toolkit.md)
- [Development](./docs/development/building.md)
- [ADRs](./docs/adr/README.md)
- [Audits](./docs/audits/README.md)

## Product boundary

DataLoom owns synchronization policy, deterministic orchestration, transfer
coordination, durable recovery, and operational signals. Applications retain
their domain repositories, UI state, server contracts, authentication
credentials, and domain-specific business truth.

Payloads remain opaque to the shared engine. Generic conflict utilities can be
built in, but DataLoom must not silently invent business merge rules.

## Contributing and security

Before changing code, read [CONTRIBUTING.md](./CONTRIBUTING.md). Public API,
durable schema, module-boundary, platform, and strategy changes require
corresponding documentation and validation evidence.

Report vulnerabilities privately as described in
[SECURITY.md](./SECURITY.md). Never place credentials, tokens, personal data,
or customer payloads in commits, examples, issues, logs, or test fixtures.

## License

License status: **to be finalized before V1 publication**.
