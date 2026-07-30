# Circuit-protected execution gate

> **Status:** Partial V1 runtime integration. The common gate exists; production
> Android/iOS circuit stores and complete provider-path wiring remain.

`CircuitBreakerExecutionGate` joins the circuit state machine to an already
classified provider or retry operation.

The gate performs this ordered flow:

1. acquire permission for one explicit `CircuitBreakerScope`;
2. reject without invoking the operation when the circuit is open, a probe is in
   flight, the clock regressed, persistence failed, or contention was exhausted;
3. invoke the operation at most once when allowed;
4. record `Success` or an eligible `Failure` against the same scope and probe
   generation; and
5. return the exact classified operation outcome when recording succeeds or no
   state mutation is required.

The operation must return `CircuitProtectedOperationResult`. Providers and
pipelines remain responsible for mapping platform failures to canonical,
sanitzed `DataLoomError` values and deciding whether a failure is eligible to
contribute to circuit state.

The gate deliberately does not catch exceptions. Caller cancellation and
unexpected programming failures propagate unchanged and are not converted into
circuit failures.

Circuit persistence failures after operation execution are returned explicitly;
the gate never reports the operation as durably recorded when the state update
failed.
