# DL-040 AC-FUNC-004 Common Qualification Checkpoint

## Decision

The assembled common retry and circuit runtime now has one executable
AC-FUNC-004 reference flow. The flow validates retry timing, durable circuit
state consumption after runtime recreation, open-circuit rejection, persisted
half-open probe ownership, and recovery without substituting a fake provider
adapter for the runtime integration boundary.

This is common-runtime qualification evidence. It does not complete Book 2
AC-FUNC-004, DL-040, or DataLoom V1 because it does not simulate operating
system process death or execute against the Android Room and Apple file-backed
stores in separate processes.

## Executable evidence

`RetryCircuitFunctionalQualificationTest` uses the production:

- `StandardRetryPolicy` and `SynchronizationRetryEvaluator`;
- `CircuitBreakerCoordinator` and `CircuitBreakerExecutionGate`; and
- `CircuitBreakerTransportOperationAdapter` around a fault-injecting transport
  provider.

The deterministic scenario proves:

1. full-jitter retry selections of 40 ms and 75 ms from exponential maxima of
   100 ms and 200 ms;
2. the first recoverable provider failure leaves the circuit closed with one
   consecutive failure;
3. the second failure opens the circuit with an exact persisted deadline;
4. a recreated coordinator reading the same state store rejects work before
   invoking the provider;
5. at the exact open deadline, one runtime acquires the half-open probe lease
   and an independently recreated runtime is rejected as `PROBE_IN_FLIGHT`;
6. the successful probe closes the circuit and preserves its probe generation;
   and
7. the next normal provider operation executes successfully.

The test also asserts provider invocation counts so rejected attempts cannot be
silently treated as executed work.

## Validation

The common test source compiles with the repository's Kotlin 2.4.10 toolchain,
and the AC-FUNC-004 test method executes successfully through a focused runner.
Permanent JVM, Android, and Apple validation lanes remain required on the final
review commit.

## Remaining acceptance work

- execute the same reference flow against the Android Room circuit store after
  a real database close/reopen and application-process recreation;

  **Resolved.** `RoomRetryCircuitFunctionalQualificationInstrumentedTest`
  (`docs/audits/DL-040-ac-func-004-android-room-qualification.md`) does this on
  a Gradle Managed Device.
- execute it against the Apple file-backed circuit store after store/runtime
  recreation;

  **Resolved.** `AppleFileRetryCircuitFunctionalQualificationTest`
  (`docs/audits/DL-040-ac-func-004-apple-qualification.md`) does this in-process
  on `iosSimulatorArm64Test`.
- add true concurrent and multi-process probe contention on mandatory platform
  paths;

  **Resolved 2026-09-28 for the circuit's half-open probe on both platforms**
  (`AndroidCircuitBreakerProbeContentionInstrumentedTest`, `#346`; Apple job
  `apple-process-contention-proof`, `#399`/`#400`) -- see
  `DL-040-ac-func-004-android-room-qualification.md` and
  `DL-040-ac-func-004-apple-qualification.md` for the evidence and residuals.
  A separate queue-lease contention lock is still only partly covered (Apple
  `continue-on-error`, no Android test); see those two documents' own
  "Remaining acceptance work" sections.
- add process termination between failure, scheduled retry, open deadline, and
  half-open probe completion; and

  **Resolved 2026-09-28 for Android (since 2026-08-17/18) and Apple (since
  2026-09-15/16)**, in both cases as a genuine OS/Simulator process
  kill/relaunch proving persisted state survives, not as a same-process
  simulation. Neither platform's proof re-drives the real gate after the
  relaunch (see the two platform checkpoints' own updates).
- map the resulting evidence back to every Book 2 AC-FUNC-004 step before
  changing the DL-040 or V1 release verdict.

  Not attempted by this reconciliation pass; Book 2's own text is not in this
  repository (confirmed by the `#94` qualification audit,
  `docs/status/dl-040-qualification-matrix.md`, section 2). The only in-repo
  statement of the acceptance sentence is issue `#94`'s own text and the header
  comment of `AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest.kt`.
