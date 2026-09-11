package io.dataloom.queue.room

/**
 * Shared method names, argument keys, and result-bundle keys used by
 * [ResolvedConflictDecisionContentionContentProviderA],
 * [ResolvedConflictDecisionContentionContentProviderB], and
 * [AndroidResolvedConflictDecisionLogContentionInstrumentedTest] to exchange a
 * real genuine cross-process concurrent-`record` contention proof across two
 * separate Android OS process boundaries, for
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] -- the
 * sibling durable domain
 * [UnresolvedConflictContentionContract]'s own class doc names as sharing the
 * identical [RoomDurableStateStore] persistence path but "not separately
 * proven here" for contention.
 *
 * Field-for-field mirror of [UnresolvedConflictContentionContract], not a new
 * design: both domains share the identical
 * [io.dataloom.api.state.DurableStateStore] compare-and-set contract, differing
 * only in the record type and [io.dataloom.api.state.DurableStateCodec]
 * plugged in. The two additions, [KEY_RESOLVER_ID] / [KEY_DECISION_KIND],
 * reflect [io.dataloom.api.conflict.ResolvedConflictDecisionRecord]'s extra
 * fields beyond [io.dataloom.api.conflict.UnresolvedConflictRecord] -- the
 * same two fields
 * [ResolvedConflictDecisionLogProcessTerminationContract] already adds over
 * [ConflictLogProcessTerminationContract].
 *
 * This contract is served by two genuinely different provider classes --
 * [ResolvedConflictDecisionContentionContentProviderA] and
 * [ResolvedConflictDecisionContentionContentProviderB], both extending the
 * shared [ResolvedConflictDecisionContentionContentProviderBase] -- declared
 * once each in `src/androidTest/AndroidManifest.xml` under two different
 * authorities and two different `android:process` values --
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
internal object ResolvedConflictDecisionContentionContract {
    /** Authority of the provider instance hosted in the [PROCESS_SUFFIX_A] process. */
    const val AUTHORITY_A: String = "io.dataloom.queue.room.test.resolvedconflictca"

    /** Process suffix declared for the [AUTHORITY_A] provider instance. */
    const val PROCESS_SUFFIX_A: String = ":resolvedconflictca"

    /** Authority of the provider instance hosted in the [PROCESS_SUFFIX_B] process. */
    const val AUTHORITY_B: String = "io.dataloom.queue.room.test.resolvedconflictcb"

    /** Process suffix declared for the [AUTHORITY_B] provider instance. */
    const val PROCESS_SUFFIX_B: String = ":resolvedconflictcb"

    /**
     * Opens (or no-ops against) the on-disk database named by `arg`, forcing
     * Room/SQLite to create the schema and this provider's host process to
     * start, without recording any decision. Used purely to warm up a process
     * before a race so neither racer wins solely because the other was still
     * cold-starting.
     */
    const val METHOD_WARM_UP: String = "warmUp"

    /**
     * Records one real [io.dataloom.api.conflict.ResolvedConflictDecisionRecord]
     * for the fixed [ConflictId][io.dataloom.api.identifier.ConflictId] this
     * contract uses, through the production
     * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog.record]
     * call backed by a real [RoomDurableStateStore] connection to the on-disk
     * database named by `arg`. Returns the raw outcome classification plus the
     * recorded facts, without executing anything else -- so this proves
     * commit-once contention in isolation.
     */
    const val METHOD_RECORD_DECISION: String = "recordDecision"

    /**
     * Opens a brand-new connection to the same on-disk database and returns
     * whatever durable-state record is currently persisted for the fixed
     * conflict id -- including its raw `record_version`, so the caller can
     * assert exactly one write (version 0) reached the row. `arg` is the
     * on-disk database name to open.
     */
    const val METHOD_READ_DECISION_STATE: String = "readDecisionState"

    /** This process's pid ([Int]), from [android.os.Process.myPid]. */
    const val KEY_PID: String = "pid"

    /**
     * One of "RECORDED"
     * ([io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome.Recorded]),
     * "ALREADY_RECORDED"
     * ([io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded]),
     * "CONFLICT"
     * ([io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome.Conflict] --
     * would mean the two racers wrote disagreeing facts, a test-setup bug),
     * "PERSISTENCE_FAILURE", or "CONTENTION_LIMIT" for the remaining
     * [io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome]
     * cases. Set only by [METHOD_RECORD_DECISION].
     */
    const val KEY_OUTCOME: String = "outcome"

    /** "FOUND", "MISSING", or "LOAD_FAILED" -- durable-state load result. Set only by [METHOD_READ_DECISION_STATE]. */
    const val KEY_STATUS: String = "status"

    /**
     * Persisted `record_version` ([Long]) of the durable-state row, or -1 if
     * no row exists. `0` proves exactly one insert and zero compare-and-set
     * overwrites reached the row. Set only by [METHOD_READ_DECISION_STATE].
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

    /** [io.dataloom.api.identifier.ConflictResolverId] value that produced the decision. */
    const val KEY_RESOLVER_ID: String = "resolverId"

    /** [io.dataloom.api.conflict.ResolvedConflictDecisionKind] name. */
    const val KEY_DECISION_KIND: String = "decisionKind"

    /** [io.dataloom.api.conflict.ResolvedConflictDecisionRecord.committedAt] epoch milliseconds. */
    const val KEY_COMMITTED_AT_MILLIS: String = "committedAtMillis"
}
