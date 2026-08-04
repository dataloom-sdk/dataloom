# DL-039B queued strategy-decision correspondence checkpoint

## Scope

This slice closes the resolver-contract gap after durable strategy-decision
persistence. Application-owned queued-work resolvers must return the exact
`PersistedStrategyDecision` stored on the acquired `QueueEntry`.

## Accepted invariants

- Legacy queue entry plus legacy resolved work (`null` / `null`) remains valid.
- An exact non-null decision is forwarded unchanged.
- A changed, dropped, or invented decision returns
  `DL-Q-STRATEGY-DECISION-MISMATCH` as a non-recoverable configuration failure.
- Validation occurs before workflow timeout enforcement, clock access,
  coordinator execution, provider resolution, protected facade invocation,
  retry evaluation, or a queue transition.
- Both direct and provider-protected queued handlers use the same correspondence
  boundary and canonical error.
- Failure diagnostics contain no dynamic decision, plan, or profile identifiers.
- No public API, ABI, durable schema, Apple file format, dependency, permission,
  or platform capability changes in this slice.

## Evidence

Focused common tests cover null/null compatibility, exact match, changed,
dropped, invented, and redacted failure cases. Direct-handler integration proves
that a mismatch invokes no synchronization pipeline. Protected-handler
integration proves that a mismatch invokes no protected facade and returns no
provider/circuit evidence; exact non-null correspondence continues normally.
Permanent PR, Android, and Apple workflows provide unchanged platform regression
coverage on the final immutable head.

## Remaining DL-039B work

The persisted decision remains bounded identity, not a complete immutable
`StrategyExecutionPlan`. The next architecture slice must load or reconstruct
the accepted ordered operations, required capabilities, origin, consistency,
and fallback branch without current-policy re-evaluation. Complete offline-
first, cache-first, hybrid, adaptive, conflict/event integration, process-loss
proof, and native Android/KMP Android/KMP iOS reference matrices remain open.
