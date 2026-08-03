# DL-040 Retry and Circuit Telemetry Checkpoint

## Decision

Retry/circuit execution and administration may emit through an additive,
bounded, exporter-neutral pipeline whose failure cannot change business
results. This advances FR-RETRY-010 and the retry/circuit integration portions
of FR-EVENT-006, FR-EVENT-012, and NFR-OBS-001–011. It does not complete DL-040,
DL-042, or DataLoom V1.

## Implemented boundary

- closed schema-versioned retry/circuit signal taxonomy;
- exact correlation and trace propagation from synchronization context;
- non-suspending submission with one bounded queue per exporter;
- explicit drop-latest overflow behavior and counters;
- independent exporter workers and cooperative time budgets;
- stable structured-log and trace adapters;
- fixed-enum metric dimensions that exclude all dynamic identities and error
  codes;
- redacted health/support snapshot with saturated counters; and
- exact-result wrappers for retry orchestration, circuit execution, retry
  administration, and circuit administration.

## Qualification evidence

Focused common tests cover full-buffer overflow, slow exporters, exporter
exceptions, exporter timeouts, cross-exporter isolation, adversarial dynamic
correlation identities, structured logs, trace propagation, and exact retry
result preservation. A separate common consumer compiles the public
configuration, exporter, structured sink, submission, and snapshot surface.

Exact JVM and Kotlin/Native ABI snapshots and the permanent JVM, Android, and
Apple validation lanes are required on the final review commit.

## Remaining work

- generic canonical operational/audit envelope and centralized redaction;
- durable outbox, acknowledgement, replay, retention, filtering, ordering, and
  schema upcasting;
- monotonic duration measurement and sampling;
- conflict, queue, provider, asset, plugin, and enterprise instrumentation;
- complete operations read model and deployable reference dashboard/adaptor;
- executable process-loss and full AC-FUNC-004 qualification; and
- remaining strategy, conflict, asset, plugin, enterprise, platform, and
  release gates.
