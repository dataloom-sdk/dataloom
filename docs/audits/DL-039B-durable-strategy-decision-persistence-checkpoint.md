# DL-039B durable strategy decision persistence checkpoint

## Scope

This slice connects the accepted strategy decision to durable queue work and
preserves that bounded identity in the in-memory, Android Room, and Apple queue
stores. It follows the fail-closed admission boundary merged in PR #163.

## Accepted invariants

- Effective strategy is concrete; `ADAPTIVE` may be requested but is never the
  persisted effective strategy.
- A rejected decision cannot be persisted as durable work.
- Submission preflight rejects changed, dropped, or invented strategy identity
  before queue-provider, timeout, or circuit policy.
- Queue transitions preserve decision ID, plan ID, requested strategy,
  effective profile, effective strategy, configuration version, and disposition.
- Android schema 7 migrates legacy rows with all decision columns null.
- Apple snapshot version 3 reads versions 1 and 2 without inventing a decision.
- Partial durable decision groups fail closed on Android and Apple.
- Strategy profile, decision, and plan identifiers are capped at 256 characters.
- `PersistedStrategyDecision`, `QueueEntry`, and queued-work diagnostics exclude
  dynamic strategy identifiers, payloads, metadata, credentials, exception
  text, and provider values.

## Evidence

Focused common tests cover decision invariants, submission correspondence, and
in-memory retry/deferral/recovery preservation. Android managed-device tests
cover close/reopen plus retry, deferral, and expired-lease recovery. Migration
coverage validates 6 to 7 without data invention. iOS Simulator tests cover
version-3 round trip, version-2 backward read, corrupt partial decision
rejection, and production file-provider preservation through reopen, retry,
deferral, and expired-lease recovery. Exact JVM and Kotlin/Native ABI
declarations, Room schema evidence,
external-consumer compilation, XCFramework/header validation, and Swift smoke
compilation remain permanent PR gates.

## Remaining DL-039B work

This does not complete offline-first or the six-strategy engine. Queued execution
must next require the persisted decision, resolve or reconstruct the immutable
accepted plan without current-policy re-evaluation, and prove that retry,
restart, lease recovery, configuration rollout/rollback, conflict handling, and
events cannot silently alter it. Cache-first, hybrid, adaptive execution, full
platform reference flows, and the complete acceptance matrix remain open.

## Dashboard reconciliation

The repository landing page now records closed foundation gate #93 as the first
accepted V1 gate, renumbers the remaining dependency order, and distinguishes
durable strategy-decision identity from the still-missing immutable execution-
plan replay and complete strategy runtimes.

## Living-document reconciliation

The current strategy API guide and system overview now distinguish accepted
strategy-decision persistence and retry/circuit foundations from the still-
missing immutable plan replay, remaining strategy runtimes, process-loss
qualification, conflict application, and platform reference matrices. Historical
and point-in-time audits remain unchanged; the documentation hub routes readers
through the audit index and evidence hierarchy.
