package io.dataloom.queue.room

/**
 * Shared method names, argument keys, and result-bundle keys used by
 * [UnresolvedConflictContentionContentProviderA],
 * [UnresolvedConflictContentionContentProviderB], and
 * [AndroidUnresolvedConflictLogContentionInstrumentedTest] to exchange a real
 * genuine cross-process concurrent-`record` contention proof across two
 * separate Android OS process boundaries.
 *
 * Structurally identical to [CircuitBreakerProbeContentionContract] (the
 * circuit-breaker domain's own cross-process contention contract), not to
 * [ConflictLogProcessTerminationContract] (one second process, called
 * sequentially before/after a kill). This contract is served by two
 * genuinely different provider classes --
 * [UnresolvedConflictContentionContentProviderA] and
 * [UnresolvedConflictContentionContentProviderB], both extending the shared
 * [UnresolvedConflictContentionContentProviderBase] -- declared once each in
 * `src/androidTest/AndroidManifest.xml` under two different authorities and
 * two different `android:process` values --
 * [AUTHORITY_A]/[PROCESS_SUFFIX_A] and [AUTHORITY_B]/[PROCESS_SUFFIX_B] -- so
 * the test can drive two genuinely separate, concurrently-racing OS processes
 * against the same on-disk durable-state database at once. Two distinct
 * classes are required, not one class declared twice: Android's
 * `PackageManagerService` addresses every component by `ComponentName`
 * (package + class name) and only supports one live registration per
 * `ComponentName` at runtime, even though a class declared twice in the
 * manifest compiles and packages without error -- see
 * [CircuitBreakerProbeContentionContentProviderBase]'s class doc for the
 * real-device `Unknown authority` failure that trap was found by.
 *
 * Kept as plain string/const constants (not a shared interface) because the
 * two sides communicate only through [android.content.ContentResolver.call],
 * which is itself untyped -- there is no compiled contract between separate
 * OS processes.
 */
internal object UnresolvedConflictContentionContract {
    /** Authority of the provider instance hosted in the [PROCESS_SUFFIX_A] process. */
    const val AUTHORITY_A: String = "io.dataloom.queue.room.test.unresolvedconflictca"

    /** Process suffix declared for the [AUTHORITY_A] provider instance. */
    const val PROCESS_SUFFIX_A: String = ":unresolvedconflictca"

    /** Authority of the provider instance hosted in the [PROCESS_SUFFIX_B] process. */
    const val AUTHORITY_B: String = "io.dataloom.queue.room.test.unresolvedconflictcb"

    /** Process suffix declared for the [AUTHORITY_B] provider instance. */
    const val PROCESS_SUFFIX_B: String = ":unresolvedconflictcb"

    /**
     * Opens (or no-ops against) the on-disk database named by `arg`, forcing
     * Room/SQLite to create the schema and this provider's host process to
     * start, without recording any conflict. Used purely to warm up a process
     * before a race so neither racer wins solely because the other was still
     * cold-starting.
     */
    const val METHOD_WARM_UP: String = "warmUp"

    /**
     * Records one real [io.dataloom.api.conflict.UnresolvedConflictRecord] for
     * the fixed [ConflictId][io.dataloom.api.identifier.ConflictId] this
     * contract uses, through the production
     * [io.dataloom.api.conflict.DurableUnresolvedConflictLog.record] call
     * backed by a real [RoomDurableStateStore] connection to the on-disk
     * database named by `arg`. Returns the raw outcome classification plus the
     * recorded facts, without executing anything else -- so this proves
     * commit-once contention in isolation.
     */
    const val METHOD_RECORD_CONFLICT: String = "recordConflict"

    /**
     * Opens a brand-new connection to the same on-disk database and returns
     * whatever durable-state record is currently persisted for the fixed
     * conflict id -- including its raw `record_version`, so the caller can
     * assert exactly one write (version 0) reached the row. `arg` is the
     * on-disk database name to open.
     */
    const val METHOD_READ_CONFLICT_STATE: String = "readConflictState"

    /** This process's pid ([Int]), from [android.os.Process.myPid]. */
    const val KEY_PID: String = "pid"

    /**
     * One of "RECORDED"
     * ([io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome.Recorded]),
     * "ALREADY_RECORDED"
     * ([io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome.AlreadyRecorded]),
     * "CONFLICT"
     * ([io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome.Conflict] --
     * would mean the two racers wrote disagreeing facts, a test-setup bug),
     * "PERSISTENCE_FAILURE", or "CONTENTION_LIMIT" for the remaining
     * [io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome] cases.
     * Set only by [METHOD_RECORD_CONFLICT].
     */
    const val KEY_OUTCOME: String = "outcome"

    /** "FOUND", "MISSING", or "LOAD_FAILED" -- durable-state load result. Set only by [METHOD_READ_CONFLICT_STATE]. */
    const val KEY_STATUS: String = "status"

    /**
     * Persisted `record_version` ([Long]) of the durable-state row, or -1 if
     * no row exists. `0` proves exactly one insert and zero compare-and-set
     * overwrites reached the row. Set only by [METHOD_READ_CONFLICT_STATE].
     */
    const val KEY_VERSION: String = "recordVersion"

    /** [io.dataloom.api.conflict.ConflictType] name. */
    const val KEY_CONFLICT_TYPE: String = "conflictType"

    /** [io.dataloom.api.change.EntityReference.type] value. */
    const val KEY_ENTITY_TYPE: String = "entityType"

    /** [io.dataloom.api.change.EntityReference.id] value. */
    const val KEY_ENTITY_ID: String = "entityId"

    /** [io.dataloom.api.conflict.UnresolvedConflictChangeSummary.changeEventId] value, local side. */
    const val KEY_LOCAL_CHANGE_EVENT_ID: String = "localChangeEventId"

    /** [io.dataloom.api.conflict.UnresolvedConflictChangeSummary.changeEventId] value, remote side. */
    const val KEY_REMOTE_CHANGE_EVENT_ID: String = "remoteChangeEventId"

    /** [io.dataloom.api.conflict.UnresolvedConflictReason] name. */
    const val KEY_REASON: String = "reason"

    /** [io.dataloom.api.conflict.UnresolvedConflictRecord.committedAt] epoch milliseconds. */
    const val KEY_COMMITTED_AT_MILLIS: String = "committedAtMillis"
}
