# DL-040 half-open probe lease recovery checkpoint

## Scope

This checkpoint closes the common-runtime deadlock where a process could persist
`HALF_OPEN`, lose the only probe through cancellation or process death, and leave
the circuit permanently unable to recover.

## Implemented behavior

- every half-open probe has a persisted exclusive lease deadline;
- contenders are rejected with that deadline while the lease remains active;
- the exact deadline permits one atomic replacement probe with a new generation;
- late results from the abandoned generation are stale and cannot mutate recovery;
- a matching result at or after its own deadline is reported as expired;
- coordinator recreation uses only persisted state and requires no in-memory timer;
- deadline arithmetic is overflow-safe and fails closed when no future instant exists.

## Qualification evidence

The focused common-code suite covers active-lease rejection, exact-deadline
replacement, process/coordinator recreation, late-generation protection,
matching-result expiry, configuration validation, and representable-time
exhaustion. External consumer compilation covers JVM, `iosArm64`,
`iosSimulatorArm64`, and `iosX64`.

## Remaining V1 work

Production Android and iOS persistence, direct pipeline assembly, circuit events
and metrics, authorized administrative operations, multi-process qualification,
and final AC-FUNC-004 evidence remain required.
