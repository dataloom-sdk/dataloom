# Fragment: `#95` (DL-041) quarantine credit-back for infrastructure failures (D21)

Proposed dashboard changes for the lead to fold into `docs/status/market-readiness.md`.
`docs/status/market-readiness.md` itself was not edited.

## (a) Proposed "Recently shipped" row

| 2026-09-28 | `#95` D21: fixed a correctness defect in the D18 conflict quarantine (found while writing ADR-0011): the counter incremented on replays that followed a transient storage or provider failure, so a flaky store could quarantine a healthy entity. Only genuine repeats of a conflict now count: when an inbound batch fails with a retry-eligible error (the retry machinery's own `protectedRetryStopReason(error) == null` test) while preparing, applying or checkpointing, the occurrences that batch counted are credited back through the new `DurableConflictQuarantineLog.creditOccurrence` (bounded compare-and-set, idempotent per occurrence, never negative, never lifts a quarantine, ignores occurrences from an ended window or before a release; record codec now format version 2). Non-retryable failures, `Defer`/`Fail`/unresolved outcomes and quarantine blocks still count exactly as before; the fail-closed no-checkpoint-advance guarantee and release authorization are unchanged; nothing changes without the quarantine opt-in. ADR-0012 records D21 and the D18 section of `conflict-resolution-strategies.md` has an amendment. Verified: dataloom-api conflict JVM tests (quarantine log 42, codec 8, including concurrent occurrence/credit convergence, duplicate-credit races, window and release edge cases, restart through an encoded store), dataloom-runtime conflict, inbound-pipeline and builder quarantine JVM tests (new pipeline transient-failure suite of 9 and 2 new `DataLoomBuilder` end-to-end tests); disabling the pipeline credit makes 8 of them fail. NOT verified: Apple test execution (macOS CI). NOT built: crediting on retry-eligible failures outside the inbound pull pipeline; quarantine/release operational-event bridging. | `#95` |

## (b) Gate row

`#95` row: **unchanged**. Justification: this closes a defect in an opt-in slice, not a V1 requirement.

## (c) "Still pending" text

Remove "Crediting quarantine occurrences back when an execution fails with a retry-eligible infrastructure
error" from the `#95` open items (it was listed in ADR-0011's "What is still open").
