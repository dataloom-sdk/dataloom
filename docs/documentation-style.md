# Documentation style guide

This guide keeps DataLoom documentation consistent, reviewable, and honest
while the SDK moves toward V1.

## Audience first

Every page should answer three questions near the top:

1. Who is this for?
2. What decision or task does it support?
3. Does it describe current code, the V1 target, or both?

Prefer one primary audience per page. Link to deeper contract or audit material
instead of duplicating it.

## Status language

Use these labels consistently:

| Label | Meaning |
|---|---|
| **Current** | Verified in the checked-out repository |
| **Partial** | Some contracts or orchestration exist, but the complete behavior does not |
| **V1 required** | Mandatory before production V1; not an implementation claim |
| **Optional distribution** | Supported only if explicitly selected; not a core consumer requirement |
| **Historical evidence** | Point-in-time result that may have been superseded |

Do not use “supported,” “complete,” “production-ready,” or “qualified” for a
V1 target that is not backed by code and validation evidence.

## Product invariants

Active documentation must preserve these decisions:

- All six synchronization strategies are first-class, built-in V1
  capabilities: offline-first, remote-first, cache-first, network-only, hybrid,
  and adaptive.
- Strategy, direction, transfer mode, and trigger are independent dimensions.
- Native Android, KMP Android, and KMP iOS are mandatory V1 consumer paths.
- Native Swift/XCFramework distribution is optional and must not be confused
  with KMP iOS support.
- DataLoom owns synchronization policy and orchestration. Applications retain
  domain models, business rules, authentication credentials, UI state, and
  server contracts.
- Full retry/circuit handling, conflict handling, events/observability, asset
  transfer, plugin extensibility, and enterprise governance are V1 scope.
- V1 remains a release no-go until every release gate has evidence.

## Page structure

Use the smallest structure that serves the reader. A conceptual or integration
page usually follows:

1. Purpose and status
2. Architecture or flow
3. Contract or integration details
4. Failure, cancellation, persistence, and security semantics
5. Platform considerations
6. Verification
7. Related documentation

API pages may lead with the public type and examples. Historical audits keep
their original evidence-oriented structure.

## GitHub diagrams

Use fenced Mermaid so diagrams render in GitHub and remain version-controlled.
Choose the diagram by the relationship:

| Relationship | Diagram |
|---|---|
| Components and boundaries | `flowchart LR` |
| Decision or processing path | `flowchart LR` or `flowchart TD` |
| Calls in time order | `sequenceDiagram` |
| Durable lifecycle | `stateDiagram-v2` |

Diagram rules:

- derive names and edges from source code or accepted architecture;
- keep one question per diagram;
- use readable camelCase identifiers and short labels;
- avoid emoji, HTML, escaped newlines, and reserved identifiers;
- keep sequence diagrams to a focused scenario;
- use color only to convey status or category;
- explain the important invariant in prose immediately before or after the
  diagram; and
- provide a table when exact mappings matter.

## Kotlin and shell examples

- Prefer the Gradle Wrapper.
- Use repository-real module names and public type names.
- State host requirements before macOS, Android SDK, or network-dependent
  commands.
- Never include real credentials, tokens, personal data, or customer payloads.
- Mark pseudocode explicitly. Compilable examples must stay aligned with the
  checked-in API.

## Links and navigation

- Use relative links inside the repository.
- Link to a hub page rather than repeating a long list on every page.
- End deep pages with a short “Related documentation” section.
- Do not link to a future file unless it is added in the same change.

## Updating documentation with code

A change must update documentation when it alters:

- public API or ABI;
- strategy semantics;
- module or dependency boundaries;
- durable schemas or migration behavior;
- retry, conflict, event, asset, plugin, or governance behavior;
- platform support or build commands; or
- validation and release evidence.

Documentation review is part of the change, not a release-day cleanup.
