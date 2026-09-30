# ADR-0017: Durable audit persistence

## Status

Accepted; implemented. Completes the "durable audit persistence" item ADR-0010
left as the next slice of [ADR-0005](./ADR-0005-enterprise-governance-foundation.md)'s
D9 (tamper-evident audit).

## Date

2026-09-29

## Context

[ADR-0005](./ADR-0005-enterprise-governance-foundation.md) (D9) built a
hash-chained, tamper-evident audit log but shipped only an in-memory
[`AuditStore`](../../dataloom-governance/src/commonMain/kotlin/io/dataloom/governance/audit/AuditStore.kt):
state is lost when the process ends. [ADR-0010](./ADR-0010-governance-signed-policy-packs-and-runtime-wiring.md)
explicitly deferred durable persistence, for a specific reason: `AuditStore`
needs `head()` and `readAll()` over a growing sequence, but
[`DurableStateStore`](../../dataloom-api/src/commonMain/kotlin/io/dataloom/api/state/DurableStateStore.kt)
offers only `load(scope)` and `compareAndSet`, with no enumeration. ADR-0010
worried that a per-record layout (one `DurableStateStore` scope per audit
record, probing for the head) would freeze that shape before the retention,
overflow, and delivery questions of FR-ENT-008 were decided.

This slice (`#99`) implements durable audit persistence without needing to
answer those questions yet, by not adopting a per-record layout at all.

## Decision

### One `DurableStateStore` scope holds the whole chain

`AuditChainState` is a new `TState` holding the *entire* ordered
`List<AuditRecord>` for one [`AuditStoreScope`](../../dataloom-governance/src/commonMain/kotlin/io/dataloom/governance/audit/AuditStoreScope.kt)
(a host-supplied value, the same shape
[`OperationalEventOutboxScope`](../../dataloom-api/src/commonMain/kotlin/io/dataloom/api/operational/DurableOperationalEventOutbox.kt)
already uses — the domain does not decide partitioning, the host does, by
choosing how many `DurableAuditStore` instances it constructs). This follows
the exact precedent `DurableOperationalEventOutbox` already established in
this codebase: a growing, durably-persisted, ordered list lives as one state
value under one scope, rewritten in full on every `compareAndSet`.

This directly resolves ADR-0010's concern: because every record for a scope
lives under one *known* key, `head()` and `readAll()` are answered by a single
`DurableStateStore.load(scope)` — no per-record probing is ever needed. A
per-record layout was never required to make durable persistence work; it was
only one (rejected) way to get there.

`AuditChainState` validates its own structural invariants at construction —
records are contiguous from sequence `0` and each record's `previousMac`
equals the preceding record's `mac` — the same "a corrupt payload cannot
decode into a reordered stream" posture `OperationalEventOutboxState` already
takes. This is a *structural* check only; it never verifies a MAC
cryptographically. That remains `AuditChainVerifier`'s job, unchanged, now
running against records `DurableAuditStore.readAll()` returns instead of
`InMemoryAuditStore`'s in-memory list.

### Append is a single compare-and-set against the exact current tail

`DurableAuditStore.append(record)` loads the current `AuditChainState`,
requires `record` to extend it exactly (`record.sequence` equal to the
current record count, `record.previousMac` equal to the current head's
`mac`), and makes **one** `compareAndSet` call. Two concurrent appenders —
in one process or across processes sharing the same `DurableStateStore` — can
therefore never both persist a record at the same chain position: the
underlying store's atomic compare-and-set guarantees exactly one call lands,
and the loser's `compareAndSet` reports `Conflict`.

Unlike this codebase's other commit-once durable logs
(`DurableUnresolvedConflictLog`, `DurableStrategyDecisionEventLog`),
`DurableAuditStore.append` does **not** retry with a recomputed state on
losing that race. This is deliberate:

- An `AuditRecord` is immutable and fully formed by the time `AuditLog.append`
  calls `store.append` — its `sequence`, `previousMac`, and `mac` are already
  fixed against whatever head `AuditLog` observed. If the store has advanced
  past that head, retrying with the *same* record can never legitimately
  land: it would either duplicate a chain position or contradict the new
  head. So a lost race is reported as `AuditAppendRejectedException` with
  `HEAD_CONFLICT` — mirroring `InMemoryAuditStore.append`'s existing behavior
  for the identical situation — and the caller (`AuditLog.append`) is where a
  legitimate retry belongs: read the new head, build a fresh record from it.

### Idempotency: deliberately none at the append layer

An `AuditRecord` has no caller-supplied identifier the way
`UnresolvedConflictRecord` has `ConflictId`; its identity is purely
positional. Treating a byte-identical replay of an already-committed record
as a silent "already applied" success would require distinguishing "the same
append retried" from "an old record replayed," with no evidence beyond
equality — a distinction this slice does not have grounds to make safely, and
getting it wrong could let stale or replayed content appear to succeed
without being re-verified against the *current* chain.

`DurableAuditStore.append` therefore always requires the exact current head to
be extended, full stop; there is no `AlreadyAppended` outcome. The legitimate
form of "retry" for an audit append is always "derive a fresh record from
whatever the head actually is," which `AuditLog.append` already does on every
call — a caller recovering from a failed append simply calls it again.

### Retention: none, by design, bounded by a safety limit instead

An audit trail that silently forgets entries defeats its own purpose — the
same reasoning `InMemoryAuditStore` already documents for refusing rather
than evicting once its capacity is reached. `AuditChainState.records` is
**never pruned**: there is no age- or count-based eviction, unlike
`DurableOperationalEventOutbox` or the conflict quarantine log.

What bounds growth instead is a fixed safety limit, the same posture every
other `DurableStateCodec` in this codebase already takes toward its own
encoded payload: `AuditChainState.MAX_RECORD_COUNT` (`10,000`, the same order
of magnitude as `OperationalEventOutboxStateCodec.MAX_ENTRY_COUNT`) and
`AuditChainStateCodec`'s own maximum encoded length (`4 MiB`, identical to
`OperationalEventOutboxStateCodec.MAX_ENCODED_LENGTH`). Once either limit is
reached, further appends fail closed with
`AuditAppendRejection.CAPACITY_EXCEEDED` rather than growing without bound.
Raising the limit, rotating to a new `AuditStoreScope`, and archiving old
chains elsewhere remain host decisions; this slice does not make them. This is
an explicit, documented V1 limitation, not an oversight: a host whose audit
volume will exceed it must plan for scope rotation or a future
higher-throughput design.

### Failures distinct from chain-integrity rejections

`AuditStore`'s contract predates a durable backing: `head()` and `readAll()`
return plain values, and `append()` only throws `AuditAppendRejectedException`
for the two conditions `InMemoryAuditStore` already models. A durable
implementation can fail for a third reason the existing vocabulary does not
cover — the store itself is unavailable. `AuditStorePersistenceException`
(carrying the underlying `DataLoomError`) is thrown for that case, keeping it
distinct from `AuditAppendRejectedException`, which always means "this
specific append could not extend the chain," never "the store failed."

### Opt-in wiring: no change needed

`DataLoomGovernanceSpec.auditStore` (from ADR-0010) is already typed as the
plain `AuditStore` port — `InMemoryAuditStore` today, "or any durable
implementation a later slice adds, without this wiring changing," as that
spec's own KDoc already stated. `DurableAuditStore` is exactly that
implementation. No change was needed to `DataLoomGovernanceSpec`,
`DataLoomBuilder`, `DefaultDataLoomGovernance`, or `DataLoomGovernance` — a
host passes a `DurableAuditStore` as `auditStore` exactly as it would pass an
`InMemoryAuditStore`. This is proven end to end by a new test
(`DataLoomBuilderGovernanceDurableAuditTest`) rather than by any wiring
change, which keeps this slice's footprint in `dataloom-runtime` to test code
only.

## Consequences

### Positive

- The audit trail survives process restarts, with no change to `AuditLog`,
  `AuditChainVerifier`, or the runtime wiring surface.
- Tamper detection is unchanged: `AuditChainVerifier` still needs only
  records, key, and an optional anchor, whichever `AuditStore` produced them.
- Resolves ADR-0010's stated blocker without needing FR-ENT-008's retention,
  overflow, and delivery questions answered first.

### Costs and risks

- No retention means an operator must plan capacity: `MAX_RECORD_COUNT`
  (10,000) per scope is a hard ceiling, not a soft warning. Hosts expecting
  higher volume must rotate `AuditStoreScope`s (or a future slice must revisit
  this).
- `compareAndSet` re-encodes the entire chain on every append (the same
  amortized cost `DurableOperationalEventOutbox` already accepts for its own
  growing list). This is a known, documented V1 simplification, not a
  regression versus any prior durable design.
- No idempotent replay at the append layer means a caller that resubmits a
  stale, already-superseded `AuditRecord` object directly (bypassing
  `AuditLog`) always sees `HEAD_CONFLICT`, never a silent success. This is a
  deliberate safety choice, documented above.

## Rejected alternatives

- **Per-record `DurableStateStore` scopes, probing for the head.** Rejected:
  this is exactly the design ADR-0010 declined to freeze prematurely, and
  offers no advantage once the whole-chain-as-one-state shape sidesteps the
  problem it exists to solve.
- **Retrying `append` internally with a recomputed record on a lost race.**
  Rejected: the record handed to `DurableAuditStore.append` is already fixed
  by `AuditLog`; only the caller can legitimately recompute it against a new
  head.
- **Treating a byte-identical replayed record as an idempotent no-op.**
  Rejected: no safe way to distinguish a legitimate retry from a replay of
  stale content at this layer; see "Idempotency" above.
- **Age- or count-based retention, matching the operational outbox or
  conflict quarantine log.** Rejected for an audit trail specifically: an
  audit log that silently forgets defeats its purpose. A bounded safety limit
  was chosen instead.

## References

- [ADR-0005](./ADR-0005-enterprise-governance-foundation.md)
- [ADR-0010](./ADR-0010-governance-signed-policy-packs-and-runtime-wiring.md)
- [Governance foundation API](../api/governance-foundation.md)
- GitHub issue #99 (DL-045)
