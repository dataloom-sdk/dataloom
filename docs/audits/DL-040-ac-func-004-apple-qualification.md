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

## Remaining Apple acceptance work

- terminate and relaunch the test host between the failure, open, and half-open
  phases;
- exercise two real processes contending for the same probe lease where the
  supported Apple deployment topology permits it;
- run the complete retry scheduling and provider-adapter reference flow on the
  mandatory KMP iOS consumer path; and
- retain the permanent Apple ABI, XCFramework, exported-header, and Swift smoke
  validation lanes on the review commit.
