# DataLoom repository instructions

These instructions apply to contributors and coding assistants working in this
repository. They describe the approved V1 direction and the safeguards needed
while the implementation is still pre-release.

## Product

DataLoom is an Android-first, Jetpack-style synchronization SDK for native
Android and Kotlin Multiplatform applications targeting Android and iOS.

V1 must provide one policy-driven engine with six built-in synchronization
strategies:

- offline-first;
- remote-first;
- cache-first;
- network-only;
- hybrid; and
- adaptive.

All six are mandatory V1 capabilities. Do not defer one to V2, reduce it to an
application-only convention, or treat synchronization direction as strategy.
Strategy, direction (`PUSH`, `PULL`, `BIDIRECTIONAL`), transfer mode (`FULL`,
`DELTA`), and trigger are independent dimensions.

The mandatory V1 consumer paths are:

- native Android;
- KMP targeting Android; and
- KMP targeting iOS.

Native Swift consumption through an XCFramework is an optional distribution
path. A native Android application remains Android-only; it does not acquire
an iOS target by using DataLoom.

## Current-versus-target language

DataLoom is in active pre-V1 development and is not production-ready. Keep a
strict distinction between:

- behavior implemented and verified in the current repository;
- an accepted V1 requirement;
- a proposal that still needs approval; and
- historical audit evidence.

Never describe a capability as supported, complete, qualified, or
production-ready without implementation and verification evidence. Older
audits are point-in-time records; use the current source, accepted ADRs, and
newest scoped readiness audit for present-tense claims.

Start with:

- [`README.md`](../README.md);
- [`docs/README.md`](../docs/README.md);
- [`docs/architecture/system-overview.md`](../docs/architecture/system-overview.md);
- [`docs/adr/ADR-0002-v1-artifact-and-foundation-architecture.md`](../docs/adr/ADR-0002-v1-artifact-and-foundation-architecture.md); and
- [`docs/audits/DL-AUDIT-004-v1-production-readiness.md`](../docs/audits/DL-AUDIT-004-v1-production-readiness.md).

## V1 capability boundary

The V1 scope includes complete, production-qualified implementations for:

- the six synchronization strategies;
- deterministic request admission, policy evaluation, and versioned execution
  plans;
- durable queueing, recovery, and restart-safe state;
- standard retry, exponential backoff, jitter, budgets, server hints, manual
  retry, and durable circuit breaking;
- built-in generic conflict policies, precedence, persistence, recovery,
  audit, loop protection, and convergence evidence;
- lifecycle, progress, retry, conflict, and operational events plus durable
  observability, metrics, tracing, health, exporters, and an operational view;
- asset upload and download, chunking, streaming, integrity, and resume;
- a permission-bounded plugin platform beyond provider interfaces; and
- enterprise administration, governance, tenant isolation, policy, and audit.

Applications continue to own their UI, domain models, repositories, server
contracts, authentication credentials, and domain-specific business truth.
DataLoom may provide generic conflict utilities but must not silently invent
business merge semantics.

## Architecture rules

- Maintain strict module boundaries and an acyclic dependency graph.
- Keep public contracts separate from implementations.
- Do not expose implementation or third-party library types in public APIs.
- Prefer immutable models and explicit dependencies.
- Shared modules must not depend on Android APIs.
- Platform functionality belongs in dedicated platform modules.
- Keep opaque application payloads out of shared policy logic.
- Prefer capability and provider interfaces for infrastructure integration.
- Avoid global mutable state, service locators, and hidden fallback.
- Make durable decisions versioned, explicit, restart-safe, and auditable.
- Do not change accepted architecture boundaries without updating or adding an
  ADR.

The current module and dependency rules are documented in
[`docs/architecture/modules.md`](../docs/architecture/modules.md).

## Strategy implementation rules

- Evaluate strategy before resolving providers.
- Persist the effective strategy and versioned execution plan for durable work.
- Reject missing capabilities with typed outcomes; never select a different
  strategy merely because a provider is unavailable.
- Keep adaptive selection deterministic, bounded, explainable, and restricted
  to approved concrete profiles.
- Keep hybrid branches finite and explicit.
- Preserve network-only's no-storage and no-queue side-effect guarantee.
- Preserve offline-first's atomic durable-admission guarantee.
- Define cache freshness, stale use, refresh, and fallback explicitly.
- Define remote-first fallback only for configured typed outcomes.
- Test process death, restart, duplicate delivery, cancellation, concurrency,
  and clock boundaries for every durable strategy.

See [`docs/strategies/README.md`](../docs/strategies/README.md).

## Kotlin and concurrency

- Use idiomatic Kotlin and explicit visibility for public and internal
  contracts.
- Prefer immutable `val` properties.
- Use sealed types for closed state models and data classes only for value
  semantics.
- Avoid unnecessary nullable values and wildcard imports.
- Add KDoc to every public API.
- Do not suppress warnings without a documented justification.
- Use structured concurrency; never use `GlobalScope`.
- Never swallow `CancellationException`.
- Do not use arbitrary sleeps for synchronization.
- Avoid blocking calls in coroutine contexts.
- Inject clocks, dispatchers, schedulers, randomness, and identifiers when
  deterministic testing requires them.
- Document thread-safety and ordering guarantees.

## Errors, privacy, and security

- Use canonical typed DataLoom errors.
- Do not expose raw infrastructure exceptions through public APIs.
- Classify terminal, retryable, deferred, conflict, and cancelled outcomes
  explicitly.
- Preserve useful diagnostics without exposing sensitive data.
- Never silently ignore failures.
- Never commit or log credentials, tokens, keys, certificates, personal data,
  or complete application payloads.
- Validate untrusted input and redact diagnostic data.
- Never weaken authentication, encryption, certificate validation, integrity
  checks, or validation gates to make a test pass.

Follow [`SECURITY.md`](../SECURITY.md).

## Dependencies and public compatibility

- Add a dependency only when the approved scope requires it.
- Prefer stable, maintained, cross-platform-compatible dependencies.
- Review licensing, security, binary size, publication, and platform impact.
- Do not expose dependency-specific types in public APIs.
- Treat public API, Kotlin ABI, durable schemas, serialized plans, event
  envelopes, plugin manifests, and artifact coordinates as compatibility
  surfaces.
- Add migration and compatibility evidence before changing a durable format.

## Testing

Every production change needs deterministic, isolated, repeatable tests.
Bug fixes need regression coverage for the root cause, not only the observed
symptom.

Test the relevant success, failure, cancellation, timeout, retry, recovery,
conflict, duplicate-delivery, and process-restart paths. Use fake clocks, test
dispatchers, controlled providers, and explicit synchronization instead of
wall-clock delays.

Use the lowest-cost validation ladder in
[`docs/development/building.md`](../docs/development/building.md). Do not use
GitHub Actions as an iterative debugger. Inspect an existing failure once,
reproduce it locally, make the coherent fix, and run only the smallest
necessary workflow after the change is ready for review. Never weaken or skip
checks merely to obtain a green result.

## Documentation

Update documentation whenever behavior, configuration, APIs, modules,
workflows, compatibility surfaces, or examples change. Follow
[`docs/documentation-style.md`](../docs/documentation-style.md).

Public API documentation should explain:

- purpose and ownership;
- inputs and outputs;
- error and cancellation behavior;
- ordering, concurrency, and thread-safety;
- durability and restart semantics;
- platform availability;
- current implementation status versus V1 target; and
- a realistic example where it improves understanding.

Keep Mermaid diagrams GitHub-compatible and pair important visuals with a text
explanation.

## Git and pull requests

- Do not push directly to `main`.
- Keep changes focused and traceable to an approved issue or explicit task.
- Do not merge pull requests, publish packages, or create releases without
  explicit human authorization.
- Preserve unrelated user changes in a dirty worktree.
- Never fabricate build, test, workflow, or release evidence.

Every pull request should report:

- issue or decision reference;
- intent and changed modules;
- strategy and platform impact;
- architecture and public compatibility impact;
- durable-data and migration impact;
- security and privacy impact;
- tests and exact validation evidence;
- documentation changes;
- known limitations and follow-up work; and
- whether a GitHub Actions run is actually necessary.

## Before completing a task

1. Re-read the approved scope and acceptance criteria.
2. Inspect the relevant architecture, current source, and tests.
3. Review every modified file and preserve unrelated work.
4. Run the smallest relevant formatting, static, unit, integration, and
   compatibility checks locally.
5. Check public API, ABI, durable schema, serialization, event, and artifact
   impact.
6. Check platform parity for native Android, KMP Android, and KMP iOS.
7. Check secrets, privacy, dependency, and supply-chain impact.
8. Update active documentation and diagrams.
9. Report commands, results, limitations, and checks not run.

Do not claim completion when required evidence is missing.
