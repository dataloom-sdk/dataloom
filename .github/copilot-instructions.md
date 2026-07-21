# DataLoom Copilot Instructions

## Product

DataLoom is an enterprise-grade offline-first synchronization SDK.

It provides:

- Synchronization orchestration
- Durable queueing
- Retry management
- Conflict resolution
- Policy evaluation
- Checkpoint recovery
- Provider extensibility
- Plugin extensibility
- Observability
- Platform integration

The host application owns:

- User interface
- Business logic
- Domain models
- Authentication flow
- Backend services
- API definitions

## Initial Platforms

The initial implementation targets:

- Kotlin
- Android
- Kotlin/JVM
- Kotlin Multiplatform where appropriate

Do not introduce iOS, JavaScript, WebAssembly, cloud services, or other
platforms unless an approved GitHub issue explicitly requires them.

## Platform Strategy

- Android is the primary reference and adoption platform.
- Shared contracts and runtime foundations use Kotlin Multiplatform where
  appropriate.
- Android-specific functionality belongs in dedicated Android modules.
- Shared modules must not depend on Android APIs.
- KMP compatibility must not delay the first complete Android vertical slice.
- Do not add new platform targets without an approved issue.
- Provider interfaces are preferred for infrastructure integrations.

## Source of Truth

Use this precedence:

1. Approved GitHub issue acceptance criteria
2. DataLoom Product Requirements Specification
3. RFC-0001 Product Architecture
4. Software Architecture
5. SDK Design Specification
6. SDK Implementation Guide
7. API Documentation
8. Developer Guide
9. Contributor Guide
10. Testing Strategy
11. Release Engineering Guide

Do not invent requirements.

When information is missing, clearly document the assumption and request
human review.

## Architecture Rules

- Maintain strict module boundaries.
- Keep public contracts separate from implementation code.
- Do not expose implementation classes through public APIs.
- Do not expose third-party library types through public APIs.
- Prefer immutable data models.
- Prefer explicit dependencies.
- Core modules must not depend on Android APIs.
- Platform modules may depend on core modules.
- Avoid circular module dependencies.
- Avoid global mutable state.
- Avoid service locators.
- Infrastructure integrations must use provider interfaces.
- Do not change architecture boundaries without an approved ADR.

## Kotlin Rules

- Use idiomatic Kotlin.
- Prefer immutable `val` properties.
- Use sealed interfaces or sealed classes for closed state models.
- Use data classes only when value semantics are appropriate.
- Avoid unnecessary nullable values.
- Use explicit visibility for public and internal contracts.
- Add KDoc to every public API.
- Do not use wildcard imports.
- Do not suppress warnings without documented justification.

## Coroutines and Concurrency

- Use structured concurrency.
- Never use `GlobalScope`.
- Never swallow `CancellationException`.
- Do not use `Thread.sleep`.
- Avoid blocking calls inside coroutine contexts.
- Inject clocks, dispatchers, and schedulers when deterministic testing is required.
- Document thread-safety guarantees.
- Test success, failure, cancellation, timeout, retry, and recovery.

## Error Handling

- Use canonical DataLoom error types.
- Do not expose raw infrastructure exceptions through public APIs.
- Classify errors as recoverable or non-recoverable.
- Preserve diagnostic context without exposing sensitive information.
- Never silently ignore failures.
- Never log credentials, tokens, keys, or complete user payloads.

## Testing

Every production change must include appropriate tests.

Tests must be:

- Deterministic
- Isolated
- Repeatable
- Independent of production services
- Fast where practical

Bug fixes must include regression tests.

Do not use arbitrary delays in tests. Use fake clocks, test dispatchers,
controlled providers, and explicit synchronization.

## Security

- Never commit passwords, tokens, certificates, signing keys, or private keys.
- Never place real credentials in source code, examples, tests, issues, or prompts.
- Use placeholders for secrets.
- Follow least-privilege principles.
- Validate untrusted input.
- Redact sensitive diagnostic data.
- Never weaken authentication, encryption, or certificate validation to make tests pass.

## Dependencies

- Add dependencies only when an approved issue requires them.
- Prefer stable and actively maintained dependencies.
- Explain why every new dependency is necessary.
- Review licensing, security, binary size, and platform compatibility.
- Do not expose dependency-specific types through public APIs.

## Documentation

Update documentation whenever behavior, configuration, APIs, modules,
workflows, or examples change.

Public API documentation must explain:

- Purpose
- Parameters
- Return behavior
- Error behavior
- Cancellation behavior
- Threading expectations
- Usage examples where useful

## Git and Pull Requests

Do not push directly to `main`.

Use focused branch names:

- `feature/DL-001-repository-foundation`
- `feature/DL-002-gradle-skeleton`
- `fix/DL-101-queue-recovery`
- `docs/DL-201-provider-guide`

Keep every pull request limited to one approved issue.

Every pull request must include:

- Issue reference
- Summary
- Files and modules changed
- Architecture impact
- API compatibility impact
- Security impact
- Testing evidence
- Documentation changes
- Known limitations

Do not merge pull requests.
Do not publish packages.
Do not create releases.
Prepare changes for human review.

## Before Completing a Task

1. Re-read the issue acceptance criteria.
2. Review every modified file.
3. Run available formatting checks.
4. Run static analysis.
5. Run relevant unit and integration tests.
6. Run the complete build when available.
7. Check for accidental public API changes.
8. Check for secrets and sensitive information.
9. Update documentation.
10. Report every command executed and its result.

Never fabricate successful build or test results.
