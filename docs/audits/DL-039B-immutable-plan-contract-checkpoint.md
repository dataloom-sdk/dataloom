# DL-039B immutable accepted-plan contract checkpoint

## Accepted behavior

- Every evaluated built-in plan that enters durable admission contains a finite
  immutable continuation selected during the original evaluation.
- Queue admission rejects identity-only plans.
- `QueueEntry` and `QueuedSynchronizationWork` carry the complete accepted plan
  beside the bounded decision identity.
- A plan must match the decision, request direction, request mode, and contain a
  durable continuation.
- Application-owned encoders and work resolvers cannot change, drop, or invent
  the plan.
- Plan mismatch stops before timeout, clock, provider/circuit, retry, execution,
  or a queue transition and reports only the redacted code
  `DL-Q-STRATEGY-PLAN-MISMATCH`.
- The deterministic bounded V1 codec round-trips identifiers, operations,
  capabilities, consistency, origin, cache state, and the finite fallback
  branch.

## Remaining integration in this branch

Android Room schema version 8, Apple queue format version 4, platform migration
and corruption evidence, and accepted-plan execution without policy
re-evaluation follow this contract checkpoint.
