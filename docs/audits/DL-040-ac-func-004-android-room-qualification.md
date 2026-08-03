# DL-040 AC-FUNC-004 Android Room Qualification Checkpoint

## Decision

The Android Room circuit store now participates in an instrumented
AC-FUNC-004 recovery flow through the production circuit execution gate. This
adds real database close/reopen and independent Room connection evidence to the
common reference flow. It does not complete DL-040 or DataLoom V1.

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

## Boundary

This is device/emulator database-reopen and concurrent-connection evidence. It
runs within one Android instrumentation process. It does not prove application
process termination or cross-process Room contention.

## Remaining Android acceptance work

- terminate and relaunch an application process between the failure, open, and
  half-open phases while retaining the same database;
- execute genuine cross-process probe contention if the supported Android
  deployment topology permits multiple workers;
- run the full retry scheduling and transport-provider reference flow on both
  native Android and KMP Android consumer paths; and
- retain the permanent Android unit, ABI, schema, migration, and managed-device
  validation lanes on the review commit.
