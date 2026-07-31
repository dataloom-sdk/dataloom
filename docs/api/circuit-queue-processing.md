# Circuit-aware bounded queue processing

> **Status:** Partial V1 runtime integration. Circuit-aware acquisition and
> lease-guarded transition processing are implemented. Expired-lease recovery,
> worker scheduling assembly, builder policy, durable iOS state, observability,
> and complete end-to-end qualification remain open.

## Purpose

`CircuitBreakerDurableQueueExecutionProcessor` is the circuit-aware counterpart
to `DurableQueueExecutionProcessor`. It performs one bounded acquisition and one
sequential handler/transition cycle while retaining provider execution and
circuit recording as separate facts.

It is additive. The historical processor and result model remain unchanged.

## Explicit scopes

`QueueProcessingCircuitScopes` supplies separate scopes for:

- acquisition;
- completion;
- retry rescheduling;
- non-retry deferral;
- failure/dead-letter transition; and
- explicit cancellation.

Every scope is validated during construction:

- provider-bearing scopes identify the adapter's queue provider;
- operation-bearing scopes identify the exact queue operation; and
- global/workflow scopes remain explicit choices.

No scope inheritance, fallback, provider inference, operation inference, tenant
inference, or workflow inference occurs.

## Ordered processing

One `process` call:

1. requests circuit permission for acquisition;
2. invokes `QueueProvider.acquire` at most once when allowed;
3. preserves acquisition provider and record evidence;
4. stops before handlers if acquisition recording is unconfirmed;
5. validates the acquired batch structurally;
6. executes entries sequentially;
7. requests circuit permission for the exact transition;
8. invokes that transition at most once when allowed; and
9. stops at the first pre-execution, provider, or recording problem.

Later entries never execute after an unconfirmed transition.

## Result model

`CircuitBreakerQueueProcessingResult` has six terminal shapes:

- `NoWork` — acquisition succeeded, circuit recording was accepted, and no
  entries were available;
- `Processed` — all acquired entries completed one confirmed provider transition;
- `PreExecutionStopped` — circuit permission stopped acquisition or a transition
  before provider invocation;
- `ProviderFailure` — provider ran and returned a canonical circuit or semantic
  failure, with the exact record result retained;
- `CircuitRecordingUnconfirmed` — provider succeeded but the later circuit
  recording was not accepted; and
- `QueueContractViolation` — acquisition succeeded but returned structurally
  invalid entries.

## Truthful counters

Provider success is the durable transition boundary.

- A pre-execution rejection does not increment a transition counter.
- A provider failure does not increment a transition counter.
- A provider success increments the exact transition counter even when the
  subsequent circuit-state recording fails.
- Handler execution increments `executed` before transition permission, matching
  the at-least-once queue model.
- Later entries stop after the first non-normal result.

For example, when completion succeeds but circuit recording fails:

```text
acquired  = 2
executed  = 1
completed = 1
```

The completed transition is not rolled back or replayed. The remaining leased
entry is left to normal lease expiry and recovery.

## Accepted circuit records

A normal `NoWork` or `Processed` result contains only operation records whose
circuit outcome is:

- `CircuitBreakerRecordResult.Recorded`; or
- `CircuitBreakerRecordResult.Ignored`.

Persistence failure, stale probe, expired probe lease, clock regression, or
contention exhaustion terminates the cycle through
`CircuitRecordingUnconfirmed`.

## Provider failures

`ProviderFailure.disposition` distinguishes:

- `CIRCUIT_FAILURE` — dependency availability failure; and
- `NON_CIRCUIT_FAILURE` — semantic failure proving the dependency responded.

The exact canonical error and exact `CircuitBreakerRecordResult` are preserved.

## Acquisition recording failure

Acquisition may lease entries successfully before circuit recording fails. The
processor then returns `CircuitRecordingUnconfirmed` with:

- the acquired count;
- the affected queue-entry identifiers;
- the confirmed lease identifier; and
- the exact failed record result.

No handler is invoked. This avoids executing work while circuit persistence is
unreliable and avoids pretending that acquisition did not happen.

## Cancellation and exceptions

Caller cancellation and unexpected handler, queue-provider, or circuit-store
exceptions propagate unchanged. If a half-open probe is abandoned, the existing
durable probe lease bounds recovery.

## Security and retention

The result model retains bounded identifiers, counters, canonical errors, circuit
decisions, and record evidence. It does not expose payload bytes, credentials,
headers, provider instances, or arbitrary metadata. Normal completed results do
not retain acquired queue entries.

## Remaining V1 work

- circuit-aware expired-lease recovery and worker scheduling coordination;
- explicit builder circuit-policy assembly;
- production KMP iOS circuit-state storage and relaunch recovery;
- circuit lifecycle events, bounded metrics, structured logs, traces, redaction,
  and correlation;
- authorized and audited circuit administration; and
- multi-process, high-contention, process-death, restart, and Book 2
  `AC-FUNC-004` qualification.
