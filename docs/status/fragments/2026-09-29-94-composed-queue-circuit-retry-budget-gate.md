# Fragment: `#94` (DL-040) composed queue/circuit loop and retry-budget relaunch gate (2026-09-29)

Addresses backlog items 2 and 3(b) from the `#94` qualification audit
(`docs/status/dl-040-qualification-matrix.md`, section 5.3 ranks 2 and 3; also
carried as items 2 and 3 of the "Still pending" list proposed in
[`fragments/2026-09-29-94-docs-reconcile.md`](2026-09-29-94-docs-reconcile.md)).
Both new/extended tests were run for real; nothing here is claimed without a
passing local run.

## (a) Proposed "Recently shipped" log entry

| Date | Change | Evidence |
|---|---|---|
| 2026-09-29 | `#94`: composed queue-worker → retry-reschedule → circuit-breaker loop proven over real Android Room stores (`ComposedQueueCircuitRobolectricTest`); Android retry-budget process-kill/relaunch proof extended to assert `availableAt` equality and re-drive the real `RoomQueueProvider.acquire` gate post-relaunch (`AndroidProcessTerminationRetryBudgetInstrumentedTest`) | `runtime-android-reference-consumer:testDebugUnitTest` (Robolectric, passed locally); `dataloom-queue-room:compileDebugAndroidTestKotlin` (compiles; real run needs `android-validation.yml`'s Gradle Managed Device, not observable from Windows) |

## (b) Status / percentage

**Proposed: 76% → 78%, two points, both from item (2)/(3b) below landing with
a real, passing test — not from any claim about item (3a) or the Apple side,
which remain exactly as open as the prior fragment described.**

- New evidence, genuinely run:
  1. `runtime-android-reference-consumer/src/test/kotlin/io/dataloom/consumer/android/ComposedQueueCircuitRobolectricTest.kt`
     drives a real `DurableQueueExecutionProcessor` (real `RoomQueueProvider`)
     wrapped around a real `ProviderProtectedQueuedSynchronizationExecutionHandler`
     (real `DataLoom.protectedSynchronization`, i.e. the real
     `CircuitBreakerExecutionGate` over a real `RoomCircuitBreakerStateStore`)
     through five worker cycles: two genuine transport failures open the real
     circuit (persisted to disk), two further cycles are rejected by the real
     gate before the transport is ever called again while genuinely
     rescheduling through the real `SynchronizationRetryEvaluator`, and the
     fifth cycle's own organically-computed backoff lands past the open
     deadline, is granted the real half-open probe, succeeds, and completes.
     Passed locally (`:runtime-android-reference-consumer:testDebugUnitTest`,
     `DATALOOM_ANDROID_BUILD=true`).
  2. `AndroidProcessTerminationRetryBudgetInstrumentedTest` now additionally
     asserts the relaunched process reads back the exact persisted
     `availableAt` (previously read back but never compared for equality —
     see the qualification matrix, section 4.2), and the relaunched process
     first re-drives a real `RoomQueueProvider.acquire` gate-check one
     millisecond before that `availableAt` and asserts `NoEntries`, proving
     the real acquisition gate — not just the raw row — still honors the
     persisted value after a genuine OS process kill. Compiles
     (`:dataloom-queue-room:compileDebugAndroidTestKotlin`); real execution
     needs `android-validation.yml`'s Gradle Managed Device (Linux/KVM), not
     observable from Windows.
- Explicitly **not** claimed or changed by this work:
  - No KMP Android or KMP iOS counterpart exists for either test (no
    KMP-shaped Android consumer exists yet; no equivalent Apple change was
    made).
  - Item (3a) — a real circuit-breaker gate (`CircuitBreakerExecutionGate`)
    re-drive after process relaunch, proving reject-before-deadline/
    probe-at-deadline/recovery post-kill — is still genuinely absent, for both
    the retry-budget and circuit-breaker kill proofs. Confirmed by re-reading
    both `AndroidProcessTerminationCircuitBreakerInstrumentedTest.kt` (its
    `readCircuitState` calls `RoomCircuitBreakerStateStore(database).load(SCOPE)`
    directly, never `CircuitBreakerExecutionGate`) and the retry-budget test's
    own prior `readRetryBudget` (only `acquire`, no reschedule/deferral replay
    of any kind). Not touched by this fragment's PR — out of the two tasks
    scoped to this slice.
  - `#94`'s composed-loop gap is not closed end to end: no equivalent test
    exists over `AppleFileQueueProvider`/`AppleFileCircuitBreakerStateStore`,
    and no Android cross-process (multi-`android:process`) version of the
    composed loop exists either (that is a separate, still-open item —
    cross-process *queue-lease* contention — tracked below).

## (c) Notes worth flagging for the lead (not acted on in this PR)

- The KDoc of `AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest.kt`
  states that a Robolectric attempt at driving `RoomCircuitBreakerStateStore`
  failed with `CIRCUIT_ROOM_DATABASE_FAILURE`, described as a genuine
  Robolectric/SQLite-shadow incompatibility with the `circuit_breaker_states`
  table. While diagnosing this slice's own test, the identical raw exception
  (`android.database.sqlite.SQLiteCantOpenDatabaseException: ... File ...
  doesn't exist ... check directory permissions`) reproduced under a long
  `RobolectricTestRunner` class/method name combined with a verbose database
  file name — the same Windows `MAX_PATH` artifact
  `AndroidReferenceConsumerDurableQueueRobolectricTest`'s own KDoc already
  documents for `RoomQueueProvider`. Shortening the test class, method, and
  database names made `RoomCircuitBreakerStateStore` work under Robolectric
  without any production change. This fragment does not assert the earlier
  claim was definitely this same artifact (a different environment could
  still hit a genuine incompatibility), but the evidence is strong enough to
  be worth a follow-up look before that KDoc's claim is repeated further.

## Ordered remaining backlog (this agent's view, for the lead to confirm)

1. Criterion 8 residual (any docs not already covered by the 2026-09-29
   docs-reconcile fragment).
2. Real circuit-breaker gate re-drive after process relaunch (item 3a above) —
   Android and Apple both.
3. Apple counterpart of the composed queue/circuit loop
   (`AppleFileQueueProvider` + `AppleFileCircuitBreakerStateStore`).
4. Android cross-process queue-lease contention test (or the documented
   single-process alternative the reconciliation audit allows).
5. `FR-RETRY-005` adapters beyond Ktor (Retrofit, GraphQL, gRPC), or a
   recorded decision to scope hints to Ktor only.
6. The "KMP Android consumer" definition decision (release-lead call, not an
   engineering gap by itself).
