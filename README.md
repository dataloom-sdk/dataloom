# DataLoom

DataLoom is an enterprise-grade offline-first synchronization SDK project.

## The problem DataLoom is designed to solve

Offline-first applications must continue to work while networks are slow,
unavailable, or intermittent. DataLoom is intended to provide shared
synchronization capabilities such as durable queueing, retry handling,
conflict management, policy evaluation, and recovery checkpoints so host
applications can focus on product-specific business logic.

## Current project status

DataLoom is in the repository foundation stage. Governance, contribution
rules, and architecture documentation are being established before SDK
implementation begins.

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

## Documentation structure

- [Contributing Guide](./CONTRIBUTING.md)
- [Security Policy](./SECURITY.md)
- [Code of Conduct](./CODE_OF_CONDUCT.md)
- [Documentation Index](./docs/README.md)
  - [Architecture](./docs/architecture/README.md)
  - [Architecture Decision Records](./docs/adr/README.md)
  - [Specifications](./docs/specifications/README.md)

## Contribution status

Contributions are welcome through approved issues and pull requests that follow
the repository governance documents.

## Security reporting

Please report vulnerabilities privately following the process in
[SECURITY.md](./SECURITY.md).

## License

License status: **To be finalized**.
