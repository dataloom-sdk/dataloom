# Operational Envelope Wire Format

Status: **available foundation**. The canonical V1 frame codec and explicit
schema-upcast registry are implemented. Durable delivery and subsystem-wide
adoption remain open.

## Frozen V1 frame

`OperationalEnvelopeWireCodec` encodes exactly one
`OperationalEventEnvelope`. The format is platform-independent and does not
depend on JVM serialization, reflection, locale, or map iteration order.

| Order | Field | Encoding |
|---:|---|---|
| 1 | Magic | Four bytes: `44 4c 4f 50` (`DLOP`) |
| 2 | Wire version | Big-endian signed 32-bit integer; V1 is `1` |
| 3 | Envelope identity and routing | Length-prefixed UTF-8 strings in constructor order |
| 4 | Envelope schema and time | Big-endian 32-bit schema version and 64-bit epoch milliseconds |
| 5 | Correlation and optional identities | Required UTF-8 correlation; nullable strings use length `-1` |
| 6 | Payload descriptor | Type, schema, encoding, classification, and optional encoded byte count |
| 7 | Redacted attributes | Count followed by key/value UTF-8 pairs in ascending key order |

Every non-null string uses a four-byte length followed by exact UTF-8 bytes.
An optional long uses one byte (`0` absent, `1` present) followed by an
eight-byte value when present. No raw payload bytes or application objects are
part of this frame.

The golden-layout test freezes the complete byte sequence of a minimal V1
record. Any intended format change requires a new wire version and migration
evidence; it must not silently rewrite V1.

## Bounds and untrusted input

- Frames larger than 1 MiB are rejected before parsing.
- Individual UTF-8 fields are bounded to 16 KiB.
- Attribute count, key syntax, and redacted-value bounds are revalidated.
- Attribute keys must be strictly ordered, preventing alternate encodings of
  the same record.
- Invalid UTF-8, truncated fields, unknown categories/classifications,
  unsupported wire versions, duplicate/non-canonical attributes, and trailing
  bytes produce a structured rejection.
- Decode failures never contain raw frame bytes or dynamic field content.

`OperationalEnvelopeDecodeResult` separates `Decoded` from `Rejected` and
uses `OperationalEnvelopeDecodeFailure` for stable failure classification.
Callers must not retry malformed or unsupported frames as transient transport
failures.

## Envelope schema upcasting

Wire version and envelope schema version are separate:

- wire version controls how bytes become the current envelope structure;
- envelope schema version controls the meaning of one event type.

`OperationalEnvelopeUpcasterRegistry` selects transitions by exact event type
and exact source schema version. The caller supplies the target version. Each
transition must advance monotonically and may evolve the payload descriptor or
already-redacted attributes, but it cannot change stable identity, source,
category, occurrence time, routing, or correlation.

The registry:

- rejects duplicate and non-advancing transitions at construction;
- applies at most 32 deterministic steps;
- rejects missing or overshooting paths;
- isolates ordinary upcaster exceptions without exposing messages;
- propagates cancellation; and
- validates every returned envelope before continuing.

## Example

```kotlin
val bytes = OperationalEnvelopeWireCodec.encode(envelope)
val decoded = OperationalEnvelopeWireCodec.decode(bytes)

val current = if (decoded is OperationalEnvelopeDecodeResult.Decoded) {
    upcasterRegistry.upcast(
        envelope = decoded.envelope,
        targetSchemaVersion = OperationalSchemaVersion(3),
    )
} else {
    decoded
}
```

## Remaining V1 boundary

This codec is not a durable outbox, audit store, encryption layer, integrity
signature, acknowledgement protocol, retention policy, or replay engine. It
also does not claim that every existing synchronization, retry, circuit,
conflict, asset, plugin, or governance signal has adopted the envelope.

See [Operational envelope and redaction](./operational-envelope-redaction.md)
and the
[wire compatibility checkpoint](../audits/DL-039-operational-wire-compatibility-checkpoint.md).
