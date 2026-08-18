# Conflict detection strategies

## Status

**Implemented reference-detector foundation; the full V1 conflict engine remains
open.** DataLoom supplies six deterministic, payload-opaque reference detectors
reachable through the existing `ConflictDetectorRegistry` and exact
`ConflictOrchestrationBindings` IDs.

This closes a named standard-detector gap in issue
[#95](https://github.com/dataloom-sdk/dataloom/issues/95), but does not complete
that issue. Atomic decision application, convergence, precedence, loop
protection/quarantine, manual operations, complete operational evidence, and
mandatory-platform end-to-end qualification remain release work.

## Selection model

A detector is selected only by its exact `ConflictDetectorId`. The runtime does
not select a detector from an entity type, conflict type, platform, class name,
registration order, or exception.

`ConflictDetectorRegistry` checks application registrations first, followed by
the reference catalog. Therefore an application may register its own detector
under a reference ID to intentionally replace that implementation. The
registry's public `detectors` snapshot continues to contain only application
registrations, preserving its historical size and order.

```kotlin
val bindings = ConflictOrchestrationBindings(
    detectorId = ConflictDetectorId("dataloom.builtin.vector-clock"),
    resolverId = ConflictResolverId("dataloom.builtin.manual"),
)
```

## Reference detector catalog

| Detector ID | Evidence | Conflict result |
|---|---|---|
| `dataloom.builtin.operation` | Local and remote `ChangeOperation` | Canonical create/update/delete classification; other non-identical pairs are `CONCURRENT_CHANGE` |
| `dataloom.builtin.version` | Explicit `EntityReference.version` on both changes | Unequal opaque values are `VERSION_MISMATCH`; values are compared only for equality |
| `dataloom.builtin.timestamp` | Base, local, and remote epoch-millisecond metadata | Both local and remote newer than base are `CONCURRENT_CHANGE` |
| `dataloom.builtin.etag` | Three-way base/local/remote ETag metadata | Distinct local and remote values that both diverge from base are `VERSION_MISMATCH` |
| `dataloom.builtin.vector-clock` | Strict bounded vector-clock metadata | Incomparable clocks are `CONCURRENT_CHANGE`; equal or dominating clocks are not conflicts |
| `dataloom.builtin.application-metadata` | Three opaque application revision markers | Distinct local and remote markers that both diverge from base are a `CUSTOM` conflict |

Every detector is common Kotlin, synchronous, deterministic, and side-effect
free. It performs no provider call, I/O, clock read, randomness, payload
inspection, mutation, or persistence.

## Shared preflight behavior

All reference detectors apply these checks first:

1. Exact equal `ChangeEvent` values return `NoConflict`.
2. Reusing the same `ChangeEventId` for different facts fails closed as a
   `CUSTOM` conflict with reason code
   `event-id-reused-with-different-facts`.
3. A generated conflict uses the local/remote event IDs and detector ID to
   create a deterministic, ordered conflict identity.
4. Generated conflict metadata contains only:
   - `dataloom.conflict.detector-id`; and
   - `dataloom.conflict.reason-code`.

Raw versions, timestamps, ETags, vector clocks, application markers, payloads,
and request metadata are never copied into the generated conflict metadata.
The original `ChangeEvent` contracts remain part of the conflict because they
are already the canonical local/remote structural input; durable conflict logs
continue to exclude payloads according to their own codecs.

## Fail-closed evidence policy

`ConflictDetectionResult` currently has only `NoConflict` and
`ConflictDetected`; it has no separate “indeterminate” variant. Returning
`NoConflict` when a selected detector lacks required evidence could allow the
runtime to treat an unproven state as safe.

The evidence-based reference detectors therefore fail closed. Missing, blank,
malformed, duplicated, negative, or over-bound evidence returns a `CUSTOM`
conflict carrying a bounded reason code. The application can bind that conflict
to manual review, reject, client/server preference, or a domain resolver. No
raw invalid evidence is included in diagnostics.

A reference detector should be selected only when the host commits to supplying
its required evidence. Applications that need a different unknown-evidence
policy can register a custom detector under another ID or explicitly override
the reference ID.

## Operation detector

`dataloom.builtin.operation` has no metadata requirement.

| Local | Remote | Result |
|---|---|---|
| `UPDATE` | `DELETE` | `UPDATE_DELETE` |
| `DELETE` | `UPDATE` | `DELETE_UPDATE` |
| `CREATE` | `CREATE` | `CREATE_COLLISION` |
| `DELETE` | `DELETE` | `NoConflict` |
| Any other non-identical pair | Any other non-identical pair | `CONCURRENT_CHANGE` |

The detector does not compare payload content. Two independently identified
updates are treated as concurrent structural intents even when an application
might later determine their payloads are equivalent.

## Version detector

`dataloom.builtin.version` reads `localChange.entity.version` and
`remoteChange.entity.version`:

- both present and equal → `NoConflict`;
- both present and unequal → `VERSION_MISMATCH`;
- either missing → fail-closed `CUSTOM` conflict with
  `version.evidence-missing`.

`EntityVersion` remains opaque. The detector never parses or orders it and does
not assume revision-number, timestamp, or ETag semantics.

## Timestamp detector

Required request metadata:

```text
dataloom.conflict.base.updated-at-epoch-millis
dataloom.conflict.local.updated-at-epoch-millis
dataloom.conflict.remote.updated-at-epoch-millis
```

Local and remote values may alternatively come from each corresponding
`ChangeEvent.metadata` key:

```text
dataloom.entity.updated-at-epoch-millis
```

Request-level local/remote values take precedence over event-level values. The
base value is always request metadata. All three values must parse as Kotlin
`Long`.

- `local > base` and `remote > base` → `CONCURRENT_CHANGE`;
- otherwise → `NoConflict`;
- missing or malformed value → fail-closed `CUSTOM` conflict with a specific
  bounded reason code.

This detector identifies whether both sides changed after one accepted base; it
does not choose the winner. Winner selection is a separate resolver policy.

## ETag detector

Required request metadata:

```text
dataloom.conflict.base.etag
dataloom.conflict.local.etag
dataloom.conflict.remote.etag
```

Local and remote values may alternatively come from the corresponding event:

```text
dataloom.entity.etag
```

Request-level values take precedence. Values are opaque, non-blank strings and
are compared only for equality.

- local and remote equal → `NoConflict`;
- only one side differs from base → `NoConflict`;
- local and remote are distinct and both differ from base →
  `VERSION_MISMATCH`;
- missing or blank evidence → fail-closed `CUSTOM` conflict.

## Vector-clock detector

Request-level keys:

```text
dataloom.conflict.local.vector-clock
dataloom.conflict.remote.vector-clock
```

Event-level fallback key:

```text
dataloom.entity.vector-clock
```

Encoding is a comma-separated set of `actor=counter` entries:

```text
client=4,server=2
```

Validation is strict and bounded:

- one to 64 actors;
- each actor is non-blank and at most 64 characters;
- each actor appears once;
- each counter is a non-negative Kotlin `Long`;
- each item contains exactly one `=`.

Missing actors are compared as counter `0`. If one clock is greater than or
equal to the other for every actor, the clocks are ordered and the result is
`NoConflict`. If each clock is ahead for at least one actor, they are
incomparable and the result is `CONCURRENT_CHANGE`. Invalid evidence fails
closed without persisting the raw clock.

## Application metadata detector

Required request metadata:

```text
dataloom.conflict.application.base
dataloom.conflict.application.local
dataloom.conflict.application.remote
```

These are opaque, non-blank application revision markers. DataLoom assigns no
meaning beyond equality:

- local and remote equal → `NoConflict`;
- only one side differs from base → `NoConflict`;
- distinct local and remote values that both differ from base → `CUSTOM`
  conflict;
- missing or blank evidence → fail-closed `CUSTOM` conflict.

This is useful when an application already has a safe revision marker but does
not want the shared engine to interpret its format.

## End-to-end orchestration

A host may combine reference detection and resolution without registering
custom implementations:

```kotlin
val spec = DataLoomConflictDetectionSpec(
    detectors = emptyList(),
    resolvers = emptyList(),
    bindings = ConflictOrchestrationBindings(
        detectorId = ConflictDetectorId("dataloom.builtin.operation"),
        resolverId = ConflictResolverId("dataloom.builtin.server-wins"),
    ),
    unresolvedConflictStore = unresolvedStore,
    resolvedConflictDecisionStore = resolvedStore,
)
```

Application registrations remain available for domain behavior, and an exact-ID
application registration overrides the corresponding reference implementation.

## Current execution boundary

Inbound pull invokes conflict detection before applying each accepted remote
change when conflict detection is configured. The current pipeline records and
counts detection/resolution evidence but does not yet atomically apply every
resolution decision to domain storage. Consequently, these reference detectors
improve classification and durable evidence but do not complete convergence or
AC-FUNC-002.

## Remaining V1 conflict work

Issue #95 remains open for at least:

- schema-aware field-merge reference integration;
- atomic decision application with checkpoint/outbox/audit effects;
- entity, workflow, tenant, and global policy precedence;
- fingerprints, bounded attempts, convergence limits, loop detection, and
  quarantine;
- authorized query/resolve/manual operations;
- complete event, metric, retry, redaction, and immutable audit integration;
- restart, migration, duplicate, contention, and concurrent-resolution tests;
- AC-FUNC-002; and
- native Android, KMP Android, and KMP iOS parity qualification on one reviewed
  candidate.
