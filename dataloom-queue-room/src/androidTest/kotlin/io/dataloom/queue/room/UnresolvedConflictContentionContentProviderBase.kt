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
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.conflict.UnresolvedConflictRecordCodec
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
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
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog] (`#95`'s
 * conflict-engine gate).
 *
 * This is the conflict-engine analogue of
 * [CircuitBreakerProbeContentionContentProviderBase]: where that proves two
 * genuinely separate OS processes racing for the circuit-breaker's single
 * half-open probe permit yield exactly one winner enforced by
 * `RoomCircuitBreakerStateStore`'s own compare-and-set, this proves two
 * genuinely separate OS processes racing to
 * [DurableUnresolvedConflictLog.record] the **same** [ConflictId] at the same
 * real wall-clock moment yield exactly one [DurableUnresolvedConflictRecordOutcome.Recorded]
 * and one [DurableUnresolvedConflictRecordOutcome.AlreadyRecorded] -- never
 * two `Recorded` (which would be a lost-update corruption), never a
 * `Conflict`/`PersistenceFailure`/`ContentionLimitReached` -- enforced by
 * [RoomDurableStateStore.compareAndSet] -> `DurableStateDao.compareAndSet`'s
 * real `@Insert(onConflict = IGNORE)` against the `durable_states` composite
 * primary key, which Android's SQLite driver serializes across the two
 * processes via its own file locking. Not a test-only mutex, `synchronized`
 * block, or single-process coroutine dispatcher.
 *
 * The losing racer's insert is ignored (rowid `-1`), so its
 * [DurableUnresolvedConflictLog.record] call observes
 * [io.dataloom.api.state.DurableStateCompareAndSetResult.Conflict], loops per
 * its own documented bounded retry, reloads, now sees the winner's row, finds
 * the facts agree (both racers write the identical fixed [RECORD]), and
 * returns [DurableUnresolvedConflictRecordOutcome.AlreadyRecorded]. The
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
 * [UnresolvedConflictContentionContentProviderA] and
 * [UnresolvedConflictContentionContentProviderB] -- each declared exactly
 * once under its own authority/`android:process`, both extending this shared
 * base so the proof logic is written once.
 *
 * Every entry point opens a fresh [DataLoomRoomDatabase] connection to the
 * on-disk database named by the call argument and drives the real
 * [DurableUnresolvedConflictLog]/[RoomDurableStateStore] production
 * persistence path -- never touching the underlying Room table directly.
 */
public abstract class UnresolvedConflictContentionContentProviderBase : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val databaseName = requireNotNull(arg) {
            "UnresolvedConflictContentionContentProviderBase requires a database name argument."
        }
        val appContext = requireNotNull(context) {
            "UnresolvedConflictContentionContentProviderBase has no attached Context."
        }
        return when (method) {
            UnresolvedConflictContentionContract.METHOD_WARM_UP -> {
                runBlocking { warmUp(appContext, databaseName) }
            }
            UnresolvedConflictContentionContract.METHOD_RECORD_CONFLICT -> {
                runBlocking { recordConflict(appContext, databaseName) }
            }
            UnresolvedConflictContentionContract.METHOD_READ_CONFLICT_STATE -> {
                runBlocking { readConflictState(appContext, databaseName) }
            }
            else -> error("Unknown UnresolvedConflictContentionContentProviderBase method: $method")
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
            putInt(UnresolvedConflictContentionContract.KEY_PID, android.os.Process.myPid())
        }
    }

    private suspend fun recordConflict(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val bundle = Bundle().apply {
                putInt(UnresolvedConflictContentionContract.KEY_PID, android.os.Process.myPid())
            }
            when (val outcome = log(database).record(CONFLICT_ID, RECORD)) {
                is DurableUnresolvedConflictRecordOutcome.Recorded -> {
                    bundle.putString(UnresolvedConflictContentionContract.KEY_OUTCOME, "RECORDED")
                    putRecordFacts(bundle, outcome.record)
                }
                is DurableUnresolvedConflictRecordOutcome.AlreadyRecorded -> {
                    bundle.putString(UnresolvedConflictContentionContract.KEY_OUTCOME, "ALREADY_RECORDED")
                    putRecordFacts(bundle, outcome.record)
                }
                is DurableUnresolvedConflictRecordOutcome.Conflict -> {
                    bundle.putString(UnresolvedConflictContentionContract.KEY_OUTCOME, "CONFLICT")
                    putRecordFacts(bundle, outcome.existing)
                }
                is DurableUnresolvedConflictRecordOutcome.PersistenceFailure -> {
                    bundle.putString(UnresolvedConflictContentionContract.KEY_OUTCOME, "PERSISTENCE_FAILURE")
                }
                DurableUnresolvedConflictRecordOutcome.ContentionLimitReached -> {
                    bundle.putString(UnresolvedConflictContentionContract.KEY_OUTCOME, "CONTENTION_LIMIT")
                }
            }
            return bundle
        } finally {
            database.close()
        }
    }

    private suspend fun readConflictState(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val bundle = Bundle().apply {
                putInt(UnresolvedConflictContentionContract.KEY_PID, android.os.Process.myPid())
                putLong(UnresolvedConflictContentionContract.KEY_VERSION, -1L)
            }
            when (val loaded = store(database).load(CONFLICT_ID)) {
                is ProviderOperationResult.Failure -> {
                    bundle.putString(UnresolvedConflictContentionContract.KEY_STATUS, "LOAD_FAILED")
                }
                is ProviderOperationResult.Success -> when (val value = loaded.value) {
                    is DurableStateLoadResult.Found -> {
                        bundle.putString(UnresolvedConflictContentionContract.KEY_STATUS, "FOUND")
                        bundle.putLong(UnresolvedConflictContentionContract.KEY_VERSION, value.record.version)
                        putRecordFacts(bundle, value.record.state)
                    }
                    DurableStateLoadResult.Missing -> {
                        bundle.putString(UnresolvedConflictContentionContract.KEY_STATUS, "MISSING")
                    }
                }
            }
            return bundle
        } finally {
            database.close()
        }
    }

    private fun putRecordFacts(bundle: Bundle, record: UnresolvedConflictRecord) {
        bundle.putString(UnresolvedConflictContentionContract.KEY_CONFLICT_TYPE, record.conflictType.name)
        bundle.putString(UnresolvedConflictContentionContract.KEY_ENTITY_TYPE, record.entity.type.value)
        bundle.putString(UnresolvedConflictContentionContract.KEY_ENTITY_ID, record.entity.id.value)
        bundle.putString(
            UnresolvedConflictContentionContract.KEY_LOCAL_CHANGE_EVENT_ID,
            record.localChange.changeEventId.value,
        )
        bundle.putString(
            UnresolvedConflictContentionContract.KEY_REMOTE_CHANGE_EVENT_ID,
            record.remoteChange.changeEventId.value,
        )
        bundle.putString(UnresolvedConflictContentionContract.KEY_REASON, record.reason.name)
        bundle.putLong(
            UnresolvedConflictContentionContract.KEY_COMMITTED_AT_MILLIS,
            record.committedAt.epochMilliseconds,
        )
    }

    private fun store(database: DataLoomRoomDatabase): RoomDurableStateStore<ConflictId, UnresolvedConflictRecord> =
        RoomDurableStateStore(
            database,
            NAMESPACE,
            DurableUnresolvedConflictLog.KeyEncoder,
            UnresolvedConflictRecordCodec(),
        )

    private fun log(database: DataLoomRoomDatabase): DurableUnresolvedConflictLog =
        DurableUnresolvedConflictLog(store(database))

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
        const val NAMESPACE: String = "unresolved-conflicts"
        val CONFLICT_ID = ConflictId("conflict-contention-concurrent-record")
        val RECORD = UnresolvedConflictRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("note"), EntityId("note-contention-1")),
            localChange = UnresolvedConflictChangeSummary(
                ChangeEventId("local-contention-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.Empty,
            ),
            remoteChange = UnresolvedConflictChangeSummary(
                ChangeEventId("remote-contention-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.Empty,
            ),
            conflictMetadata = DataLoomMetadata.Empty,
            reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
            committedAt = DataLoomInstant(10_000L),
        )
    }
}
