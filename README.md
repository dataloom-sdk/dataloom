# DataLoom

DataLoom is an enterprise-grade offline-first synchronization SDK project.

## Current project status

DataLoom is in the **Gradle multi-module project foundation** stage (DL-002).

The Gradle build system, Kotlin Multiplatform module structure, convention
build logic, and dependency direction have been established. No synchronization
algorithms or production SDK features have been implemented yet.

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
| Initial platform target | Kotlin Multiplatform JVM |

## Module overview

| Module | Purpose |
|---|---|
| `dataloom-api` | Future stable public contracts, models, and error types |
| `dataloom-core` | Internal platform-independent foundation |
| `dataloom-runtime` | Future synchronization runtime and engine coordination |
| `dataloom-testing` | Future testing utilities and fake providers |

See [Module Architecture](./docs/architecture/modules.md) for dependency
rules and boundaries.

## Basic build command

Use the Gradle Wrapper (no separate Gradle installation required):

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

## Documentation

- [Module Architecture](./docs/architecture/modules.md)
- [Local Build Instructions](./docs/development/building.md)
- [Foundational API Contracts (DL-004)](./docs/api/foundational-contracts.md)
- [Error Model (DL-004)](./docs/api/error-model.md)
- [Contributing Guide](./CONTRIBUTING.md)
- [Security Policy](./SECURITY.md)
- [Code of Conduct](./CODE_OF_CONDUCT.md)
- [Documentation Index](./docs/README.md)
  - [Architecture](./docs/architecture/README.md)
  - [Architecture Decision Records](./docs/adr/README.md)
  - [Specifications](./docs/specifications/README.md)

## The problem DataLoom is designed to solve

Offline-first applications must continue to work while networks are slow,
unavailable, or intermittent. DataLoom is intended to provide shared
synchronization capabilities such as durable queueing, retry handling,
conflict management, policy evaluation, and recovery checkpoints so host
applications can focus on product-specific business logic.

## Planned platforms

- Kotlin
- Android
- Kotlin/JVM
- Kotlin Multiplatform (where appropriate)

## High-level architecture (planned)

- Core synchronization orchestration and state management
- Durable operation queue and retry coordination
- Conflict resolution and policy evaluation
- Provider and plugin extensibility points
- Observability and integration layers

## Contribution status

Contributions are welcome through approved issues and pull requests that follow
the repository governance documents.

## Security reporting

Please report vulnerabilities privately following the process in
[SECURITY.md](./SECURITY.md).

## License

License status: **To be finalized**.
