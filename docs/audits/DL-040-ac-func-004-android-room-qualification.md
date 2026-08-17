# DL-040 AC-FUNC-004 Android Room Qualification Checkpoint

## Decision

The Android Room circuit store now participates in an instrumented
AC-FUNC-004 recovery flow through the production circuit execution gate. This
adds real database close/reopen and independent Room connection evidence to the
common reference flow, and a separate instrumented test now adds real
application-process kill/relaunch evidence on top of that. It does not
complete DL-040 or DataLoom V1.

## Executable evidence

`RoomRetryCircuitFunctionalQualificationInstrumentedTest` proves that:

- two eligible failures open the circuit with an exact persisted deadline;
- the database can be closed and reopened before an open-circuit rejection;
- rejected work does not invoke the protected operation;
- at the exact deadline, one Room-backed coordinator acquires the durable
  half-open probe lease;
- a coordinator using an independent Room database connection observes the
  lease and rejects competing work as `PROBE_IN_FLIGHT`;
- the successful probe closes the circuit while preserving the generation; and
- a final database close/reopen reads the recovered state.

The dependency on `:dataloom-runtime` is instrumented-test-only; it does not add
a production dependency from the Room provider to the runtime.

`AndroidProcessTerminationCircuitBreakerInstrumentedTest` proves genuine OS
process termination/relaunch, closing the "terminate and relaunch an
application process" item this checkpoint previously listed as remaining
work. It drives the real `CircuitBreakerExecutionGate` through
`CircuitBreakerProcessTerminationContentProvider`, a second component Android
hosts in its own `:circuitproof` process (`src/androidTest/AndroidManifest.xml`):

- one call into that process opens the circuit and returns the persisted
  state plus that process's real pid;
- `ActivityManager.killBackgroundProcesses` terminates the `:circuitproof`
  process outright, polled via `ActivityManager.getRunningAppProcesses` until
  confirmed gone (the test fails loudly on timeout rather than assuming
  success);
- a second call causes Android to relaunch `:circuitproof` from scratch
  (content providers start their host process on first access); its pid is
  asserted to differ from the first, proving a genuinely new OS process, not
  a warm one; and
- the circuit-breaker state that fresh process reads back from a brand-new
  Room connection to the same on-disk database is asserted to match exactly
  what was persisted before the kill (phase, open deadline, consecutive
  failures, probe generation).

## Boundary

`RoomRetryCircuitFunctionalQualificationInstrumentedTest` is device/emulator
database-reopen and concurrent-connection evidence; it runs within one Android
instrumentation process and does not by itself prove application process
termination or cross-process Room contention.

`AndroidProcessTerminationCircuitBreakerInstrumentedTest` proves real process
termination/relaunch for the Android Room circuit-breaker store specifically,
using `ActivityManager.killBackgroundProcesses` against a second
manifest-declared process (`:circuitproof`) rather than an external
host/`adb`-controlled kill -- this repository has no AndroidX Test
Orchestrator or equivalent host-controlled test-runner infrastructure, so a
genuinely separate OS process reachable from within instrumentation is the
strongest mechanism currently available here. It still does not exercise
cross-process *contention* for the half-open probe lease (two processes racing
for the same lease), and it does not run the full retry-scheduling/
transport-provider AC-FUNC-004 flow through a composed `DataLoomBuilder`
instance.

## Remaining Android acceptance work

- execute genuine cross-process probe contention if the supported Android
  deployment topology permits multiple workers;
- run the full retry scheduling and transport-provider reference flow on both
  native Android and KMP Android consumer paths; and
- retain the permanent Android unit, ABI, schema, migration, and managed-device
  validation lanes on the review commit.
