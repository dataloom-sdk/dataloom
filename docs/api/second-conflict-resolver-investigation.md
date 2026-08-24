# Second deterministic conflict resolver: investigation

## Question

Issue [#95](https://github.com/dataloom-sdk/dataloom/issues/95)'s market-readiness
row named this "still pending" gap: ship additional deterministic built-in
conflict-resolution strategies beyond `LastWriteWinsConflictResolver`,
including a genuinely evidence-based recency resolver, once reliable
timestamp evidence exists on the conflict/change contracts.

This investigation checked whether that gap is genuinely open today.

## Finding: the gap was already closed

It was already closed, on 2026-08-18, by
[`#329`](https://github.com/dataloom-sdk/dataloom/commit/42cf0fcfa6fbb28a0d27dbeca3c10aeea7812b0b)
("Add deterministic built-in conflict policies"). The market-readiness row's
"Still pending" cell was simply never updated to remove the clause once that
PR shipped — the row's "Recently shipped" narrative also never mentioned
`#329` by name, jumping straight from `#328` to `#345`. This is a documentation
staleness bug, not an open product gap. Both are corrected by this change; see
"Correction applied" below.

`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/conflict/BuiltInConflictResolvers.kt`
today registers, alongside `LastWriteWinsConflictResolver`, five further
built-in `ConflictResolver` policies, each selected only by its exact
`ConflictResolverId` through the existing `ConflictResolverRegistry`:

| Resolver ID | Decision | What it is |
|---|---|---|
| `dataloom.builtin.client-wins` | `UseLocal` | Explicit, honestly-named always-prefer-local policy. |
| `dataloom.builtin.server-wins` | `UseRemote` | Explicit, honestly-named always-prefer-remote policy. |
| `dataloom.builtin.timestamp` | Newest of two explicit epoch-millisecond values wins; missing/malformed defers | The genuinely evidence-based recency resolver the gap asked for. |
| `dataloom.builtin.reject` | `Fail` | Conflicting work must stop under the selected policy. |
| `dataloom.builtin.manual` | `Defer` | Conflict remains durable for a later manual workflow. |

Full semantics for each are already documented in
[`conflict-resolution-strategies.md`](./conflict-resolution-strategies.md).

## How the "no reliable timestamp evidence" constraint was actually satisfied

`docs/api/conflict-resolution-strategies.md` and the `#95` row both correctly
state that no reliable recency evidence exists as a *field* on
`SynchronizationConflict`, `ChangeEvent`, or `EntityReference`. This
investigation re-confirmed that directly against the current contracts
(`dataloom-api/src/commonMain/kotlin/io/dataloom/api/conflict/SynchronizationConflict.kt`,
`.../change/ChangeEvent.kt`, `.../change/EntityReference.kt`) — none of the
three declares any `timestamp`, `occurredAt`, `Instant`, or `Clock`-derived
property. `LastWriteWinsConflictResolver` therefore still correctly returns
only a stable `UseRemote` placeholder, and its KDoc/docs page correctly say so.

`#329`'s `dataloom.builtin.timestamp` resolver does not add a timestamp field
to those contracts. Instead it reads two exact, documented metadata keys —
`dataloom.conflict.local.updated-at-epoch-millis` and
`dataloom.conflict.remote.updated-at-epoch-millis` — from
`ConflictResolutionRequest.metadata` first and `SynchronizationConflict.metadata`
second (both already-existing, already-generic `DataLoomMetadata` fields), and
defers rather than guessing when either value is absent or fails to parse as a
`Long`. This is a real, working way to satisfy "once reliable timestamp
evidence exists": the evidence is supplied explicitly by the calling
application through the existing metadata channel, rather than requiring a new
required field on the shared contracts (which the field-level-merge boundary
section of the same doc explains DataLoom deliberately avoids, since the
shared engine cannot itself judge what counts as trustworthy recency for an
opaque payload).

## Other candidates considered, and why nothing further is needed right now

The task also asked whether a *third* resolver — an `EntityReference`/
`ChangeEventId`-ordering tie-break, or an explicit always-local/always-remote
policy — would add genuine value beyond what exists.

- **Explicit always-local / always-remote**: already shipped as
  `dataloom.builtin.client-wins` / `dataloom.builtin.server-wins` in `#329`.
  Nothing further to add here.
- **`EntityReference`/`ChangeEventId`-ordering tie-break**: considered and
  rejected. `ChangeEventId` and `EntityReference` carry no documented
  ordering guarantee (they are opaque identifiers, not sequence numbers), so
  a resolver built on their raw ordering would produce a decision that looks
  deterministic per-call but encodes no meaningful real-world precedence —
  it would not honestly represent anything about which change actually
  happened first or which side an application would want to win. Shipping it
  would add a selectable policy nobody could correctly reason about, which
  is worse than not shipping it.
- **Per-conflict-type / per-entity-type automatic resolver selection**:
  `ConflictResolverRegistry.lookup` (in
  `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/conflict/ConflictResolverRegistry.kt`)
  selects only by exact `ConflictResolverId`; there is no per-type routing
  layer. This is already tracked separately in the `#95` row as the still-open
  "policy precedence (entity > workflow > tenant > global)" item — it is a
  routing/precedence gap, not a missing-resolver gap, so it is left alone here
  rather than folded into this investigation's scope.

No further built-in resolver was implemented as a result of this
investigation. `#329` already shipped the genuinely valuable set (explicit
local-wins, explicit remote-wins, evidence-based recency, reject, manual), and
no additional candidate examined here would add real, honestly-justified
selectable value.

## Correction applied

`docs/status/market-readiness.md`'s `#95` row:

- "Recently shipped" narrative now names `#329` and its five policies, in its
  correct chronological position between `#328` and `#345`.
- "Still pending" cell no longer lists "ship additional deterministic
  built-in strategies beyond last-write-wins" — that work already shipped.
  The remaining, still-genuinely-open items (policy precedence, loop/
  non-convergence quarantine, authorized manual operations, and the rest)
  are unchanged.

The `#95` percentage is unchanged. This is a documentation-accuracy
correction reflecting already-existing, already-tested functionality, not a
delivery of new functionality in this change.
