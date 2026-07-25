# DataLoom

DataLoom is an Android-first, Jetpack-style offline synchronization SDK with a
Kotlin Multiplatform-ready shared core.

## Current project status

DataLoom is in active SDK development.  The synchronization runtime, provider
contracts, facade, durable queue, retry orchestration, conflict handling,
observer delivery, and connectivity-aware execution are implemented.
Apple-platform (iOS) support via Kotlin/Native and XCFramework is established
in DL-036.

## Required JDK

Java Development Kit **17 or newer** is required.

```bash
java -version
```

## Toolchain

| Tool | Version |
|---|---|
| Gradle Wrapper | 9.5.0 |
| Kotlin | 2.4.10 |
| JVM bytecode target | 17 |
| Platform targets | JVM · iosArm64 · iosSimulatorArm64 · iosX64 |

## Platform Support

| Platform | Target | Status |
|---|---|---|
| Kotlin / JVM | `jvm` | ✓ Active |
| Physical iPhone / iPad | `iosArm64` | ✓ DL-036 |
| Apple-silicon iOS Simulator | `iosSimulatorArm64` | ✓ DL-036 |
| Intel iOS Simulator | `iosX64` | ✓ DL-036 |
| Android | `android` | Planned |
| Desktop | — | Not planned |

Apple-platform support requires macOS with Xcode.  See
[docs/apple/README.md](./docs/apple/README.md) for details.

## Module Overview

| Module | Purpose |
|---|---|
| `dataloom-api` | Stable public contracts, models, provider interfaces |
| `dataloom-core` | Platform-independent runtime foundations |
| `dataloom-runtime` | Synchronization runtime, facade, pipelines, queue |
| `dataloom-testing` | Test utilities and fake providers |
| `dataloom-apple` | Apple XCFramework umbrella (macOS only) |

See [Module Architecture](./docs/architecture/modules.md) for dependency
rules and boundaries, and
[Platform Strategy (DL-006)](./docs/architecture/platform-strategy.md) for
Android-first and Kotlin Multiplatform architecture direction.

## Basic Build Command

Use the Gradle Wrapper (no separate Gradle installation required):

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

## Apple XCFramework

To assemble the DataLoom XCFramework (requires macOS with Xcode):

```bash
./gradlew :dataloom-apple:assembleDataLoomReleaseXCFramework
```

Output: `dataloom-apple/build/XCFrameworks/release/DataLoom.xcframework`

See [XCFramework Integration](./docs/apple/xcframework-integration.md) for details.

## Documentation

- [Module Architecture](./docs/architecture/modules.md)
- [Platform Strategy (DL-006)](./docs/architecture/platform-strategy.md)
- [Local Build Instructions](./docs/development/building.md)
- [Apple Platform Support (DL-036)](./docs/apple/README.md)
- [Foundational API Contracts (DL-004, DL-005)](./docs/api/foundational-contracts.md)
- [Error Model (DL-004)](./docs/api/error-model.md)
- [Execution Context (DL-005)](./docs/api/execution-context.md)
- [Synchronization Request (DL-005)](./docs/api/synchronization-request.md)
- [Payload Contracts (DL-008)](./docs/api/payload-contracts.md)
- [Change Model (DL-008)](./docs/api/change-model.md)
- [Provider SPI (DL-007)](./docs/api/provider-spi.md)
- [Provider Lifecycle and Health (DL-007)](./docs/api/provider-lifecycle.md)
- [Conflict Contracts (DL-014)](./docs/api/conflict-contracts.md)
- [Contributing Guide](./CONTRIBUTING.md)
- [Security Policy](./SECURITY.md)
- [Code of Conduct](./CODE_OF_CONDUCT.md)
- [Documentation Index](./docs/README.md)
  - [Architecture](./docs/architecture/README.md)
  - [Architecture Decision Records](./docs/adr/README.md)
    - [ADR-0001: Android-first and Kotlin Multiplatform-ready core architecture](./docs/adr/ADR-0001-android-first-kmp-core.md)
  - [Specifications](./docs/specifications/README.md)

## The Problem DataLoom Is Designed to Solve

Offline-first applications must continue to work while networks are slow,
unavailable, or intermittent. DataLoom provides shared synchronization
capabilities such as durable queueing, retry handling, conflict management,
policy evaluation, and recovery checkpoints so host applications can focus
on product-specific business logic.

## Planned Platforms

- Kotlin
- Android
- Kotlin/JVM
- iOS via Kotlin/Native and XCFramework (DL-036)
- Kotlin Multiplatform (where appropriate)

## High-Level Architecture

- Core synchronization orchestration and state management
- Durable operation queue and retry coordination
- Conflict resolution and policy evaluation
- Provider and plugin extensibility points
- Observability and integration layers

## Contribution Status

Contributions are welcome through approved issues and pull requests that follow
the repository governance documents.

## Security Reporting

Please report vulnerabilities privately following the process in
[SECURITY.md](./SECURITY.md).

## License

License status: **To be finalized**.
