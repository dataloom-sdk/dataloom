# DL-039 Operational Wire Compatibility Checkpoint

## Decision

DataLoom operational envelopes now have one frozen, deterministic V1 byte
format and one explicit bounded schema-upcast mechanism. Platform stores,
exporters, and future outbox implementations must use this boundary rather
than JVM serialization, reflection, unordered maps, or subsystem-local
version logic.

This advances #93 and #96 but does not close either issue.

## Implemented boundary

- `DLOP` magic plus explicit wire version;
- deterministic big-endian integers and length-prefixed UTF-8 fields;
- canonical ascending attribute order;
- 1 MiB frame, 16 KiB field, attribute-count, key, and value bounds;
- structured rejection for malformed, oversized, unsupported, non-canonical,
  and trailing input;
- exact round-trip preservation of all envelope and payload-descriptor fields;
- event-type/source-version upcaster selection;
- monotonic, bounded multi-step transitions;
- stable identity, routing, time, source, category, and correlation protection;
- ordinary upcaster failure isolation and cancellation propagation; and
- published-style external-consumer compilation coverage.

## Qualification evidence

Focused common tests freeze a golden V1 frame, prove full round trips and
map-order-independent output, and reject empty, oversized, invalid-magic,
unsupported-version, truncated, trailing, invalid-UTF-8, unsafe-key, and
non-canonical-order inputs.

Focused runtime tests cover multi-step upcasting, no-op targets, missing and
overshooting paths, older targets, duplicate/non-advancing registration,
identity mutation, ordinary exceptions, and cancellation. Strict Kotlin 2.4.10
compilation covers production API/runtime sources and the external consumer.

Permanent JVM, Android/KMP, Kotlin/Native ABI, XCFramework, header, Swift-smoke,
and external-consumer checks remain required on the reviewed commit.

## Remaining acceptance work

- adapt existing lifecycle, progress, retry, circuit, conflict, administration,
  and future subsystem signals to the envelope;
- implement atomic durable outbox persistence, ordering, acknowledgement,
  replay, retention, filtering, and exporter isolation;
- define integrity, encryption, signed policy, residency, and tamper-evident
  audit behavior;
- provide concrete migration fixtures whenever an event schema advances; and
- qualify decoding/upcasting through restart and platform-store migrations.
