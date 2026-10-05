# Fragment: Android cross-process queue-lease contention proof for #94 (2026-10-05)

## (a) Proposed "Recently shipped" row

| 2026-10-05 | `#94`: Android cross-process queue-lease (retry-budget lease) contention proof, `AndroidQueueLeaseContentionInstrumentedTest` in `dataloom-queue-room/src/androidTest`. Two genuinely separate OS processes (`:queueleasea`/`:queueleaseb`, two distinct `ContentProvider` classes under two authorities, same layout as the circuit-breaker probe-contention proof), each with its own Room connection to one on-disk database, race `RoomQueueProvider.acquire` for a single `RETRY_WAITING` entry over 25 rounds, released on a shared wall-clock instant. Asserted per round: exactly one process wins with the persisted retry attempt and retry budget intact, the other gets `NoEntries` (never a second lease, never a database failure), the loser's never-granted lease id is rejected `QUEUE_STALE_LEASE`, and the loser's process reads back the winner's rescheduled retry attempt/budget. **Executed** on the local `Pixel_8_Pro` AVD (Android 16 image): the new test passed 5 of 5 consecutive runs (24-25 of 25 rounds had overlapping call intervals, wins split between the two processes in every run), and the whole `:dataloom-queue-room:connectedDebugAndroidTest` suite passed 39/39 with it present. Revert-and-observe: removing both the `@Transaction` on `QueueEntryDao.acquireEntries` and the `state IN ('PENDING','RETRY_WAITING')` guard in `updateToLeased` made the test fail (`round 1: A=WON B=WON`); removing either alone did not (two independent layers, so the test proves their combined behavior). **Not verified:** a run in `android-validation.yml`'s Gradle Managed Device job (it will pick the test up automatically; no workflow change was needed or made); physical-device run. No production code changed. |

## (b) Gate row percentage

`#94`: unchanged (79%) proposed. This closes one named evidence gap (test and
execution evidence, no new production capability); the lead may judge a +1
bump if it considers the queue-lease half of "genuine cross-process contention"
now closed on both platforms for the Android side.

## (c) "Still pending" text

Remove: "Android has no cross-process queue-lease contention test (or a
documented single-process-topology rationale in its place)".

Add (residual): the new test is verified locally on one emulator only; the
`android-validation.yml` Gradle Managed Device job has not yet run it. The
Apple retry-budget lease job (`apple-retry-budget-lease-contention-proof`)
remains `continue-on-error`.

## Stale text elsewhere (not edited here, lead may fold)

`docs/status/dl-040-qualification-matrix.md` (item at line ~40 and rank-5 row),
`docs/audits/DL-040-current-acceptance-reconciliation.md` (item 4 and the
"queue-lease contention" sentence near line 186),
`docs/audits/DL-040-ac-func-004-common-qualification.md` and
`DL-040-ac-func-004-apple-qualification.md` still say Android has no
queue-lease contention test. Only
`docs/audits/DL-040-ac-func-004-android-room-qualification.md` was updated in
this PR.
