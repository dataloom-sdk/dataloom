# Fragment: `#101` Android retry behavior during builder-assembled queue replay (2026-10-05)

For the lead to fold into `docs/status/market-readiness.md`. Not edited by
this PR itself.

## (a) Proposed "Recently shipped" row

| 2026-10-05 | `#101`: Android reference-consumer proof of **retry during queue replay**, through a real `DataLoomBuilder`-assembled `DataLoom.queueWorker` over a real Room-backed queue (`RetryQueueRobolectricTest`, 2 Robolectric tests, `--rerun-tasks` run: whole module 12 tests, 0 failures, 0 errors, per the JUnit XML). Scenario 1: an offline-first plan is durably admitted (`DurablyEnqueued`, zero transport calls); worker cycle 1's replay hits a recoverable `NETWORK` transport failure and the real `SynchronizationRetryEvaluator` over a real `StandardRetryPolicy` (fixed 1500 ms backoff) reschedules it (`rescheduled == 1`, `availableAt` at least the backoff after the cycle start); cycle 2, run inside the backoff window, gets `QueueProcessingResult.NoWork` from the real Room `acquire` (transport untouched); after a real wall-clock wait (`runBlocking`, `SystemDataLoomClock`, never virtual time) cycle 3 re-acquires the entry from Room with `retryAttempt == 1` (observed by the work resolver), the second transport call succeeds and the entry completes; a further cycle finds no work. Scenario 2: transport always fails, `maximumAttempts = 1`: first failure rescheduled, second terminally `failed` (attempt limit), then `NoWork`. Revert-and-observe: forcing the replay mapper's next attempt to a constant `RetryAttempt(1)` fails scenario 2; making the Room reschedule query not persist `retry_attempt_number` fails both. **Not proven here:** the circuit-breaker half (see below), persisted `retryBudgetState` (the builder's queue-worker evaluator has no `RetryBudgetConfiguration`, so it is `null`), conflict detection during replay, an emulator run of this test (it is a JVM/Robolectric `src/test` class, not an `androidTest`), iOS (owned elsewhere), and a real WorkManager tick or process kill between attempts. | `#101` platform |

## (b) Gate row percentage

Unchanged (80%). It closes the retry half of one named "Still pending" clause
on Android only; the clause as a whole (circuit-breaker and conflict-detection
during replay, iOS counterpart) stays open, so no honest percentage move.

## (c) "Still pending" text

Replace "retry/circuit-breaker/conflict-detection behavior during queue replay
itself (each proven entry always succeeds on its first attempt) remains open"
with: "circuit-breaker and conflict-detection behavior during queue replay
remain open (retry during replay is proven on Android through a real
builder-assembled queue worker by `RetryQueueRobolectricTest`; iOS counterpart
not yet)".

Add this finding (verified against current source, not a new defect claim):
`DataLoomBuilder.queueWorkerConfiguration` and `circuitQueueWorkerConfiguration`
both build `QueuedSynchronizationExecutionHandler` over the plain
`SynchronizationExecutionCoordinator` (`buildQueueWorker` / `buildCircuitQueueWorker`);
`providerProtectionConfiguration`'s transport circuit wraps only
`DataLoom.protectedSynchronization`. `CircuitBreakerTransportOperationAdapter`
does not implement `TransportProvider`, so a protected transport cannot be
registered in its place. Consequence: a transport circuit cannot be opened or
observed through a builder-assembled queue worker; `circuitQueueWorkerConfiguration`
gates only the queue-provider operations. The only composed
queue -> retry -> transport-circuit proof remains the hand-assembled
`ComposedQueueCircuitRobolectricTest` (`#449`). Closing that at builder level
needs a small production change (wire `ProviderProtectedQueuedSynchronizationExecutionHandler`
into the builder's queue worker when `providerProtectionConfiguration` is also
set) and is the proposed next slice, ABI-affecting and so left for a lead
decision.
