package io.dataloom.queue.room

/**
 * Shared method names, argument keys, and result-bundle keys used by
 * [QueueLeaseContentionContentProviderA], [QueueLeaseContentionContentProviderB],
 * and [AndroidQueueLeaseContentionInstrumentedTest] for the genuine
 * cross-process queue-lease contention proof.
 *
 * Same two-class/two-authority/two-process layout as
 * [CircuitBreakerProbeContentionContract] (see that object's doc for why two
 * distinct provider classes, never one class declared twice, are required).
 * All calls travel through the untyped [android.content.ContentResolver.call],
 * so this object is the only "compiled" contract between the OS processes.
 *
 * The queue entry under contention is a RETRY_WAITING entry carrying real
 * retry-budget state, so the proof covers the retry-budget lease: whichever
 * process wins the lease sees the persisted retry attempt/budget intact, and
 * the retry-budget write the winner performs while holding the lease is the
 * one the loser then reads back.
 */
internal object QueueLeaseContentionContract {
    const val AUTHORITY_A: String = "io.dataloom.queue.room.test.queueleasea"
    const val PROCESS_SUFFIX_A: String = ":queueleasea"
    const val AUTHORITY_B: String = "io.dataloom.queue.room.test.queueleaseb"
    const val PROCESS_SUFFIX_B: String = ":queueleaseb"

    /** Opens and caches this process's own connection to the on-disk database named by `arg`. */
    const val METHOD_OPEN: String = "open"

    /** Closes this process's cached connection to the database named by `arg`. */
    const val METHOD_CLOSE: String = "close"

    /**
     * Enqueues entry [EXTRA_ENTRY_ID] and drives it through the real
     * acquire -> reschedule path so it is persisted as RETRY_WAITING with
     * retry attempt [SEEDED_RETRY_ATTEMPT] and a real retry-budget window.
     */
    const val METHOD_SEED: String = "seed"

    /**
     * Spins until the wall-clock instant [EXTRA_START_AT_MS] (both processes
     * share the device clock) and then calls the real
     * [RoomQueueProvider.acquire] with [EXTRA_LEASE_ID].
     */
    const val METHOD_RACE_ACQUIRE: String = "raceAcquire"

    /** Calls [RoomQueueProvider.complete] for [EXTRA_ENTRY_ID] with [EXTRA_LEASE_ID]. */
    const val METHOD_COMPLETE: String = "complete"

    /**
     * Calls [RoomQueueProvider.reschedule] for [EXTRA_ENTRY_ID] with
     * [EXTRA_LEASE_ID], persisting retry attempt [RESCHEDULED_RETRY_ATTEMPT]
     * and the matching retry-budget state.
     */
    const val METHOD_RESCHEDULE: String = "reschedule"

    /** Acquires with [EXTRA_LEASE_ID] at a later logical time, i.e. an independent read-back. */
    const val METHOD_READBACK_ACQUIRE: String = "readbackAcquire"

    const val EXTRA_ENTRY_ID: String = "entryId"
    const val EXTRA_LEASE_ID: String = "leaseId"
    const val EXTRA_START_AT_MS: String = "startAtMs"

    const val KEY_PID: String = "pid"

    /** "WON" (QueueAcquireResult.Entries), "NO_ENTRIES", or "FAILURE:<error code>". */
    const val KEY_OUTCOME: String = "outcome"

    /** "OK" or "FAILURE:<error code>" for [METHOD_COMPLETE] / [METHOD_RESCHEDULE]. */
    const val KEY_RESULT: String = "result"
    const val KEY_ACQUIRED_ENTRY_ID: String = "acquiredEntryId"
    const val KEY_ACQUIRED_ENTRY_COUNT: String = "acquiredEntryCount"
    const val KEY_LEASE_ID: String = "leaseId"
    const val KEY_RETRY_ATTEMPT: String = "retryAttempt"
    const val KEY_RETRY_CUMULATIVE_DELAY_MS: String = "retryCumulativeDelayMs"

    /** Epoch millis at which the acquire call began / returned, for overlap evidence. */
    const val KEY_CALL_START_MS: String = "callStartMs"
    const val KEY_CALL_END_MS: String = "callEndMs"

    const val SEEDED_RETRY_ATTEMPT: Int = 3
    const val SEEDED_CUMULATIVE_DELAY_MS: Long = 750L
    const val RESCHEDULED_RETRY_ATTEMPT: Int = 4
    const val RESCHEDULED_CUMULATIVE_DELAY_MS: Long = 1_250L
}
