# DL-040 Circuit-Aware Queue Processing Checkpoint

## Decision

Bounded queue acquisition and lease-guarded transitions now have an additive
circuit-aware processor that preserves provider execution and circuit recording
as independent evidence.

This checkpoint advances the queue-processing portion of FR-RETRY-007 through
FR-RETRY-009. It does not complete expired-lease recovery, worker scheduling, or
builder circuit assembly.

## Safety invariants

- Scope bindings are explicit for acquisition and every transition.
- Scope mismatch fails during construction before state-store or provider access.
- Acquisition occurs at most once per processing cycle.
- Each handler is invoked at most once in provider-returned order.
- Each transition is invoked at most once.
- A pre-execution circuit result never counts as a provider transition.
- A provider failure never counts as a successful transition.
- A provider success counts before later circuit-recording failure is reported.
- Processing stops at the first non-normal circuit or provider outcome.
- Later entries are not executed after unconfirmed durable/circuit evidence.
- Caller cancellation and unexpected exceptions propagate.

## Enriched terminal results

`CircuitBreakerQueueProcessingResult` distinguishes:

1. no work after accepted acquisition recording;
2. fully processed work with accepted operation records;
3. pre-execution circuit stop;
4. provider failure with circuit/non-circuit classification;
5. provider success followed by unconfirmed circuit recording; and
6. structurally invalid acquired work.

No terminal result hides whether a queue-provider operation already ran.

## Partial-progress accounting

When a handler ran but transition permission was denied:

```text
executed += 1
transition counter unchanged
```

When a transition provider returned failure:

```text
executed += 1
transition counter unchanged
```

When a transition provider returned success but circuit recording failed:

```text
executed += 1
matching transition counter += 1
```

The cycle then stops. This is the only truthful representation: durable provider
success cannot be rolled back because the independent circuit-state write failed.

## Acquisition recording ambiguity

If acquisition succeeds and leases entries but circuit recording later fails,
the processor returns:

- acquired count;
- affected queue-entry identifiers;
- lease identifier; and
- exact `CircuitBreakerRecordResult`.

No handler runs. Lease expiry and explicit expired-lease recovery remain the safe
reconciliation boundary.

## Normal operation records

Normal `NoWork` and `Processed` results retain ordered records only for accepted
circuit outcomes:

- `Recorded`; or
- `Ignored`.

Persistence failure, contention exhaustion, clock regression, stale probe, and
probe-lease expiry terminate through `CircuitRecordingUnconfirmed`.

## Required evidence

The review branch must prove:

- mismatched scope fails before state/provider access;
- open acquisition circuit prevents provider invocation;
- acquisition success plus record failure invokes no handler and preserves lease
  and acquired identifiers;
- all five transition types map to the correct provider operation and truthful
  counters;
- transition rejection occurs after one handler but before provider transition;
- provider transition failure preserves exact error, classification, partial
  summary, entry, lease, and record result;
- successful transition plus record failure counts the transition and stops;
- structurally invalid acquired work invokes no handler or transition;
- handler cancellation propagates;
- existing direct queue processor tests remain green;
- external consumers compile for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI baselines contain the new public contracts; and
- permanent pull-request, Android, and Apple validation pass on one clean final
  head.

## Remaining work

- circuit-aware expired-lease recovery and queue-worker coordination;
- circuit-aware scheduler result composition where needed;
- explicit `DataLoomBuilder` circuit-policy assembly;
- KMP iOS circuit-state persistence and relaunch recovery;
- circuit events, bounded metrics, structured logs, traces, redaction, and
  correlation;
- authorized and audited circuit open, close, and reset;
- multi-process, high-contention, process-death, restart, and probe recovery
  evidence; and
- complete Book 2 `AC-FUNC-004` evidence.
