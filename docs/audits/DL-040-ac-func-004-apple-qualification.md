# DL-040 AC-FUNC-004 Apple Qualification Checkpoint

## Decision

The Apple file-backed circuit store now participates in an executable
AC-FUNC-004 recovery flow through the production circuit execution gate. This
adds Apple persistence and independently recreated store/coordinator evidence
to the common reference flow. It does not complete DL-040 or DataLoom V1.

## Executable evidence

`AppleFileRetryCircuitFunctionalQualificationTest` proves that:

- two eligible failures open the circuit with the exact persisted deadline;
- a newly created file store and coordinator reject the scheduled retry without
  invoking protected work;
- at the exact deadline, one store/coordinator acquires the durable half-open
  probe lease;
- a second independently created store/coordinator observes that lease and
  rejects competing work as `PROBE_IN_FLIGHT`;
- the successful probe closes the circuit while retaining the probe generation;
  and
- a third store instance reads the exact recovered record from disk.

The test asserts operation counts so open and probe-in-flight rejections cannot
be confused with executed provider work.

## Boundary

The test recreates the file store and runtime objects and uses the real atomic
file-backed state. It runs within one test process. It is therefore restart
evidence, not proof of operating-system process termination, relaunch, or two
simultaneously executing application processes.

**Updated 2026-09-28: the three gaps this boundary names are now addressed on
real macOS CI**, each with a stated limit -- see "Remaining Apple acceptance
work" below for the detail and evidence citations.

## Remaining Apple acceptance work

- terminate and relaunch the test host between the failure, open, and half-open
  phases;

  **Resolved 2026-09-28, with a scope reduction.** CI jobs
  `apple-process-termination-proof` (`#395`, 2026-09-15, circuit-breaker state)
  and `apple-retry-budget-process-termination-proof` (`#397`, 2026-09-16,
  retry-budget state) build a real launchable iOS Simulator app, run it on a
  real `macos-15` runner, kill it with `xcrun simctl terminate`, relaunch it,
  and diff the persisted state file byte-for-byte -- a genuine OS-level
  process kill/relaunch, not a same-process simulation. Both are required jobs
  (no `continue-on-error`) in `.github/workflows/apple-validation.yml` and
  have run green on the great majority of their recent runs (61/61 and 57/57
  of the last 61/57 non-cancelled runs as of 2026-09-28). Scope reduction: the
  circuit-breaker proof app writes hand-built `CircuitBreakerState` records
  directly into `AppleFileCircuitBreakerStateStore` rather than driving
  `CircuitBreakerExecutionGate`/`CircuitBreakerCoordinator` (documented in
  `AppleCircuitBreakerProcessTerminationProof`'s own KDoc); neither proof
  re-drives the real gate after the relaunch to confirm it still rejects
  before the deadline, grants one probe at the deadline, and recovers -- that
  remains open (see `docs/apple/process-termination-proof.md`'s own "What
  remains open" section, also updated).
- exercise two real processes contending for the same probe lease where the
  supported Apple deployment topology permits it;

  **Resolved 2026-09-28 for the circuit's half-open probe.** CI job
  `apple-process-contention-proof` (`#399`, 2026-09-16; `continue-on-error`
  removed by `#400`, 2026-09-17) launches two genuinely separate,
  independently bundle-identified Simulator apps that race for the same
  half-open probe lease over a shared, unsandboxed host directory (the
  mechanism `docs/apple/cross-process-contention-investigation.md` found);
  exactly one process is allowed and the other rejected `PROBE_IN_FLIGHT`.
  This job is a required check (no `continue-on-error`). Still open: the
  *retry-budget queue lease* has a separate, still-`continue-on-error`
  Apple job (`apple-retry-budget-lease-contention-proof`, 36 green / 10 red
  job conclusions in the 80 most recent `apple-validation.yml` runs as of
  2026-09-28, all three reds since the job moved to two Simulator devices
  being a slow — 7, 23, or 26 minute — cold second-device app launch, not a
  correctness failure), and there is still no Android test for the same
  queue-lease lock at all (see
  `docs/apple/process-contention-proof.md`'s own updates for the full detail).
- run the complete retry scheduling and provider-adapter reference flow on the
  mandatory KMP iOS consumer path; and

  **Partially resolved 2026-09-28.**
  `IosReferenceConsumerRetryCircuitQualificationTest` (`#369`, 2026-08-26)
  drives the real composed `DataLoomBuilder` provider flow -- backoff/jitter,
  circuit open, rejection before the real transport, half-open probe with a
  competing store rejected `PROBE_IN_FLIGHT`, and recovery -- against the real
  `AppleFileCircuitBreakerStateStore` and `dataloom-platform-ios`'s real
  provider wiring, on `iosSimulatorArm64Test` (job `apple-validate`). Residual,
  not yet closed: retry delay in this test comes from calling
  `SynchronizationRetryEvaluator` by hand between calls, not from a durable
  queue worker rescheduling the entry through the real `AppleFileQueueProvider`.
- retain the permanent Apple ABI, XCFramework, exported-header, and Swift smoke
  validation lanes on the review commit.
