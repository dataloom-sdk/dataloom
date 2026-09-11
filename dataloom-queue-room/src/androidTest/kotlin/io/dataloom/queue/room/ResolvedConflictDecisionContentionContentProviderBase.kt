package io.dataloom.queue.room

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.ResolvedConflictDecisionRecordCodec
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlinx.coroutines.runBlocking

/**
 * Test-only shared base [ContentProvider] logic for the genuine cross-process
 * concurrent-`record` contention proof for
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] (`#95`'s
 * conflict-engine gate).
 *
 * This is the resolved-decision-log twin of
 * [UnresolvedConflictContentionContentProviderBase]: both domains share the
 * identical [RoomDurableStateStore] compare-and-set persistence path,
 * differing only in the record type
 * ([ResolvedConflictDecisionRecord] vs.
 * [io.dataloom.api.conflict.UnresolvedConflictRecord]) and the
 * [io.dataloom.api.state.DurableStateCodec] plugged in
 * ([ResolvedConflictDecisionRecordCodec] vs.
 * [io.dataloom.api.conflict.UnresolvedConflictRecordCodec]) -- so this proves
 * two genuinely separate OS processes racing to
 * [DurableResolvedConflictDecisionLog.record] the **same** [ConflictId] at
 * the same real wall-clock moment yield exactly one
 * [DurableResolvedConflictDecisionRecordOutcome.Recorded] and one
 * [DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded] -- never two
 * `Recorded` (which would be a lost-update corruption), never a
 * `Conflict`/`PersistenceFailure`/`ContentionLimitReached` -- enforced by
 * [RoomDurableStateStore.compareAndSet] -> `DurableStateDao.compareAndSet`'s
 * real `@Insert(onConflict = IGNORE)` against the `durable_states` composite
 * primary key, which Android's SQLite driver serializes across the two
 * processes via its own file locking. Not a test-only mutex, `synchronized`
 * block, or single-process coroutine dispatcher.
 *
 * The losing racer's insert is ignored (rowid `-1`), so its
 * [DurableResolvedConflictDecisionLog.record] call observes
 * [io.dataloom.api.state.DurableStateCompareAndSetResult.Conflict], loops per
 * its own documented bounded retry, reloads, now sees the winner's row, finds
 * the facts agree (both racers write the identical fixed [RECORD]), and
 * returns [DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded]. The
 * persisted `record_version` stays `0`: exactly one insert, zero
 * compare-and-set overwrites.
 *
 * IMPORTANT: this class is intentionally never declared in
 * `src/androidTest/AndroidManifest.xml` itself, and must never be -- see
 * [CircuitBreakerProbeContentionContentProviderBase]'s class doc for the full
 * `PackageManagerService` "one live registration per `ComponentName`"
 * explanation and the real-device `Unknown authority` failure an earlier
 * "one class, two authorities" attempt hit. The fix that gives each racing
 * process a distinct, independently resolvable `ComponentName` is two
 * genuinely different leaf classes --
 * [ResolvedConflictDecisionContentionContentProviderA] and
 * [ResolvedConflictDecisionContentionContentProviderB] -- each declared
 * exactly once under its own authority/`android:process`, both extending this
 * shared base so the proof logic is written once.
 *
 * Every entry point opens a fresh [DataLoomRoomDatabase] connection to the
 * on-disk database named by the call argument and drives the real
 * [DurableResolvedConflictDecisionLog]/[RoomDurableStateStore] production
 * persistence path -- never touching the underlying Room table directly.
 */
public abstract class ResolvedConflictDecisionContentionContentProviderBase : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val databaseName = requireNotNull(arg) {
            "ResolvedConflictDecisionContentionContentProviderBase requires a database name argument."
        }
        val appContext = requireNotNull(context) {
            "ResolvedConflictDecisionContentionContentProviderBase has no attached Context."
        }
        return when (method) {
            ResolvedConflictDecisionContentionContract.METHOD_WARM_UP -> {
                runBlocking { warmUp(appContext, databaseName) }
            }
            ResolvedConflictDecisionContentionContract.METHOD_RECORD_DECISION -> {
                runBlocking { recordDecision(appContext, databaseName) }
            }
            ResolvedConflictDecisionContentionContract.METHOD_READ_DECISION_STATE -> {
                runBlocking { readDecisionState(appContext, databaseName) }
            }
            else -> error("Unknown ResolvedConflictDecisionContentionContentProviderBase method: $method")
        }
    }

    private suspend fun warmUp(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            store(database).load(CONFLICT_ID)
        } finally {
            database.close()
        }
        return Bundle().apply {
            putInt(ResolvedConflictDecisionContentionContract.KEY_PID, android.os.Process.myPid())
        }
    }

    private suspend fun recordDecision(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val bundle = Bundle().apply {
                putInt(ResolvedConflictDecisionContentionContract.KEY_PID, android.os.Process.myPid())
            }
            when (val outcome = log(database).record(CONFLICT_ID, RECORD)) {
                is DurableResolvedConflictDecisionRecordOutcome.Recorded -> {
                    bundle.putString(ResolvedConflictDecisionContentionContract.KEY_OUTCOME, "RECORDED")
                    putRecordFacts(bundle, outcome.record)
                }
                is DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded -> {
                    bundle.putString(ResolvedConflictDecisionContentionContract.KEY_OUTCOME, "ALREADY_RECORDED")
                    putRecordFacts(bundle, outcome.record)
                }
                is DurableResolvedConflictDecisionRecordOutcome.Conflict -> {
                    bundle.putString(ResolvedConflictDecisionContentionContract.KEY_OUTCOME, "CONFLICT")
                    putRecordFacts(bundle, outcome.existing)
                }
                is DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure -> {
                    bundle.putString(ResolvedConflictDecisionContentionContract.KEY_OUTCOME, "PERSISTENCE_FAILURE")
                }
                DurableResolvedConflictDecisionRecordOutcome.ContentionLimitReached -> {
                    bundle.putString(ResolvedConflictDecisionContentionContract.KEY_OUTCOME, "CONTENTION_LIMIT")
                }
            }
            return bundle
        } finally {
            database.close()
        }
    }

    private suspend fun readDecisionState(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val bundle = Bundle().apply {
                putInt(ResolvedConflictDecisionContentionContract.KEY_PID, android.os.Process.myPid())
                putLong(ResolvedConflictDecisionContentionContract.KEY_VERSION, -1L)
            }
            when (val loaded = store(database).load(CONFLICT_ID)) {
                is ProviderOperationResult.Failure -> {
                    bundle.putString(ResolvedConflictDecisionContentionContract.KEY_STATUS, "LOAD_FAILED")
                }
                is ProviderOperationResult.Success -> when (val value = loaded.value) {
                    is DurableStateLoadResult.Found -> {
                        bundle.putString(ResolvedConflictDecisionContentionContract.KEY_STATUS, "FOUND")
                        bundle.putLong(ResolvedConflictDecisionContentionContract.KEY_VERSION, value.record.version)
                        putRecordFacts(bundle, value.record.state)
                    }
                    DurableStateLoadResult.Missing -> {
                        bundle.putString(ResolvedConflictDecisionContentionContract.KEY_STATUS, "MISSING")
                    }
                }
            }
            return bundle
        } finally {
            database.close()
        }
    }

    private fun putRecordFacts(bundle: Bundle, record: ResolvedConflictDecisionRecord) {
        bundle.putString(ResolvedConflictDecisionContentionContract.KEY_CONFLICT_TYPE, record.conflictType.name)
        bundle.putString(ResolvedConflictDecisionContentionContract.KEY_ENTITY_TYPE, record.entity.type.value)
        bundle.putString(ResolvedConflictDecisionContentionContract.KEY_ENTITY_ID, record.entity.id.value)
        bundle.putString(
            ResolvedConflictDecisionContentionContract.KEY_LOCAL_CHANGE_EVENT_ID,
            record.localChange.changeEventId.value,
        )
        bundle.putString(
            ResolvedConflictDecisionContentionContract.KEY_REMOTE_CHANGE_EVENT_ID,
            record.remoteChange.changeEventId.value,
        )
        bundle.putString(ResolvedConflictDecisionContentionContract.KEY_RESOLVER_ID, record.resolverId.value)
        bundle.putString(ResolvedConflictDecisionContentionContract.KEY_DECISION_KIND, record.decisionKind.name)
        bundle.putLong(
            ResolvedConflictDecisionContentionContract.KEY_COMMITTED_AT_MILLIS,
            record.committedAt.epochMilliseconds,
        )
    }

    private fun store(
        database: DataLoomRoomDatabase,
    ): RoomDurableStateStore<ConflictId, ResolvedConflictDecisionRecord> = RoomDurableStateStore(
        database,
        NAMESPACE,
        DurableResolvedConflictDecisionLog.KeyEncoder,
        ResolvedConflictDecisionRecordCodec(),
    )

    private fun log(database: DataLoomRoomDatabase): DurableResolvedConflictDecisionLog =
        DurableResolvedConflictDecisionLog(store(database))

    private fun openDatabase(context: Context, name: String): DataLoomRoomDatabase = Room.databaseBuilder(
        context,
        DataLoomRoomDatabase::class.java,
        name,
    ).addMigrations(*DataLoomRoomMigrations.ALL)
        .build()

    // ContentProvider query/insert/update/delete/getType are unused by this
    // test-only provider; call() is the sole entry point.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val NAMESPACE: String = "resolved-conflict-decisions"
        val CONFLICT_ID = ConflictId("conflict-contention-concurrent-record-resolved")
        val RECORD = ResolvedConflictDecisionRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("note"), EntityId("note-contention-resolved-1")),
            localChange = UnresolvedConflictChangeSummary(
                ChangeEventId("local-contention-resolved-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.Empty,
            ),
            remoteChange = UnresolvedConflictChangeSummary(
                ChangeEventId("remote-contention-resolved-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.Empty,
            ),
            conflictMetadata = DataLoomMetadata.Empty,
            resolverId = ConflictResolverId("dataloom.builtin.server-wins"),
            decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
            decisionMetadata = DataLoomMetadata.Empty,
            committedAt = DataLoomInstant(10_000L),
        )
    }
}
