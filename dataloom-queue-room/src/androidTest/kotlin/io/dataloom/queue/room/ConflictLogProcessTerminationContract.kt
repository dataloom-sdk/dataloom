package io.dataloom.queue.room

/**
 * Shared method names, argument keys, and result-bundle keys used by
 * [ConflictLogProcessTerminationContentProvider] and
 * [AndroidProcessTerminationConflictLogInstrumentedTest] to exchange a real
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog] proof across a
 * genuine Android process boundary.
 *
 * Mirrors [CircuitBreakerProcessTerminationContract] exactly -- plain
 * string/const constants, not a shared interface, since the two sides
 * communicate only through [android.content.ContentResolver.call], which is
 * itself untyped.
 */
internal object ConflictLogProcessTerminationContract {
    /** Authority of [ConflictLogProcessTerminationContentProvider]. Test-APK only. */
    const val AUTHORITY: String = "io.dataloom.queue.room.test.conflictproof"

    /** Process suffix declared for the provider in src/androidTest/AndroidManifest.xml. */
    const val PROCESS_SUFFIX: String = ":conflictproof"

    /**
     * Records one real [io.dataloom.api.conflict.UnresolvedConflictRecord]
     * through the production
     * [io.dataloom.api.conflict.DurableUnresolvedConflictLog.record] call and
     * returns the recorded facts. `arg` is the on-disk database name to open.
     */
    const val METHOD_RECORD_CONFLICT: String = "recordConflict"

    /**
     * Opens a brand-new connection to the same on-disk database and returns
     * whatever unresolved-conflict record is currently persisted for the
     * fixed conflict id this contract uses. `arg` is the on-disk database
     * name to open.
     */
    const val METHOD_READ_CONFLICT: String = "readConflict"

    /** This process's pid ([Int]), from [android.os.Process.myPid]. */
    const val KEY_PID: String = "pid"

    /** "RECORDED" or "MISSING" -- whether a record exists for the fixed conflict id. */
    const val KEY_STATUS: String = "status"

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
