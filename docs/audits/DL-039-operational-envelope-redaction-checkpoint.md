# DL-039 Operational Envelope and Redaction Checkpoint

## Decision

DataLoom now has one shared KMP-safe operational/audit envelope and one central,
bounded classification/redaction boundary. All remaining V1 subsystems must
adapt to these contracts instead of defining subsystem-specific dynamic maps or
diagnostic redaction rules.

This advances the canonical envelope, default data-minimization, stable
identifier, and security-foundation portions of #93. It also supplies a base
contract for #94–#99. It does not complete DL-039, DL-042, or DataLoom V1.

## Implemented boundary

- stable bounded event ID, type, source, payload type, encoding, and positive
  schema-version value types;
- closed domain, lifecycle, system, audit, telemetry, and diagnostic categories;
- explicit occurrence, correlation, causation, trace, tenant, and workflow
  fields;
- payload metadata without accepting payload bytes or arbitrary objects;
- immutable `ClassifiedData` and `RedactedAttributes` boundaries;
- public, internal, confidential, and restricted classification;
- deterministic keep, mask, remove, truncation, and overflow behavior;
- permanent removal of restricted fields and prevention of confidential keep;
- bounded ASCII keys, field counts, value lengths, and masks;
- non-sensitive redaction counters; and
- diagnostic methods that do not render dynamic identities, keys, or values.

## Qualification evidence

Focused common tests cover default classification behavior, non-configurable
restricted removal, stable map-order-independent output, field overflow,
value truncation, invalid-key rejection, empty values, self-causation rejection,
invalid schema/identity values, and adversarial diagnostic leakage. An external
common consumer constructs the public redactor and envelope surface.

The complete shared production sources compile with Kotlin 2.4.10 strict
explicit-API mode, and a focused runner executes the critical redaction,
bounding, identity-leakage, and self-causation assertions. Permanent JVM,
Android/KMP, Kotlin/Native ABI, and external-consumer lanes remain required on
the final review commit.

## Remaining acceptance work

- define and freeze canonical wire serialization plus schema upcasting;
- adapt existing synchronization and retry/circuit signals to the envelope;
- implement the durable event/audit outbox, ordering, replay, retention, and
  delivery isolation;
- add signed policy, authorization, residency, integrity, and tamper-evident
  persistence;
- add monotonic duration and secure-randomness/key-reference foundations;
- complete configuration, generalized transactional state, publication, and
  mandatory platform artifact work; and
- qualify centralized redaction against every V1 subsystem and support output.
