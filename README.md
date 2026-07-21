# DataLoom

Enterprise-grade offline-first synchronization SDK for Kotlin, Android, JVM, and Kotlin Multiplatform.

---

## What Is DataLoom?

DataLoom is an SDK that gives mobile and JVM applications a reliable, durable synchronization layer
between local storage and remote backends. Applications stay fully functional without a network
connection. When connectivity is restored, DataLoom reconciles changes automatically according to
configurable conflict-resolution policies.

DataLoom is not a database, not a backend, and not a UI framework. It is a synchronization
orchestration layer that the host application plugs into its own infrastructure.

## The Problem DataLoom Solves

Most applications treat network connectivity as a precondition for correct operation. When the
network is unavailable, writes are lost, reads stale, and error handling is ad hoc. DataLoom
inverts this model: the local device is the source of truth, and the remote backend is synchronized
to it, not the reverse.

DataLoom addresses:

- Durable local write queuing that survives process restarts
- Ordered, at-least-once delivery of operations to remote backends
- Configurable retry and backoff policies
- Deterministic conflict detection and resolution
- Checkpoint-based recovery after partial failures
- Observable sync state for UI integration

## Current Project Status

> **This project is in its foundation stage. No production SDK code has been published. No
> releases are available. The API is not stable and is subject to change without notice.**

The repository currently contains:

- Project governance documentation
- Contribution guidelines
- Architecture documentation stubs

Production SDK code will be introduced in subsequent issues.

## Planned Platforms

| Platform | Status |
|---|---|
| Kotlin/JVM | Planned |
| Android | Planned |
| Kotlin Multiplatform | Under evaluation |

iOS, JavaScript, WebAssembly, and cloud services are out of scope unless explicitly approved.

## High-Level Architecture

DataLoom is organized into layered modules with strict boundaries:

```
┌─────────────────────────────────────────────┐
│              Host Application               │
│  (UI · Business Logic · Domain Models)      │
└───────────────────┬─────────────────────────┘
                    │ SDK API
┌───────────────────▼─────────────────────────┐
│           dataloom-core                     │
│  Sync Orchestration · Queue · Retry         │
│  Conflict Resolution · Policy Evaluation    │
│  Checkpoint Recovery · Observability        │
└───────┬───────────────────────┬─────────────┘
        │ Provider API          │ Plugin API
┌───────▼──────────┐   ┌────────▼────────────┐
│ dataloom-android │   │  dataloom-plugins   │
│ Storage · Net    │   │  (extensibility)    │
└──────────────────┘   └─────────────────────┘
```

Key principles:

- Core modules must not depend on Android APIs
- Platform modules may depend on core modules
- Public contracts are separate from implementation
- No third-party types are exposed through public APIs
- Infrastructure integrations use provider interfaces

## Documentation

| Document | Description |
|---|---|
| [docs/README.md](docs/README.md) | Documentation index |
| [docs/architecture/README.md](docs/architecture/README.md) | Architecture overview |
| [docs/adr/README.md](docs/adr/README.md) | Architecture Decision Records |
| [docs/specifications/README.md](docs/specifications/README.md) | Specifications index |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |
| [SECURITY.md](SECURITY.md) | Security policy and reporting |
| [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | Community standards |

## Contributing

Contributions are not yet open to the general public. The project is in its foundation stage and
the contribution process, architecture, and API contracts are still being established.

When contributions open, all pull requests will require:

- One approved GitHub issue per pull request
- Tests for production-code changes
- Architecture approval for module-boundary or public-API changes

See [CONTRIBUTING.md](CONTRIBUTING.md) for full requirements.

## Security

Do not report security vulnerabilities through public GitHub issues. See [SECURITY.md](SECURITY.md)
for the responsible disclosure process.

## License

License to be finalized. All rights reserved until a license is published.
