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
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlinx.coroutines.runBlocking

/**
 * Test-only [ContentProvider] hosted in its own `:conflictproof` process (see
 * `src/androidTest/AndroidManifest.xml`), used exclusively by
 * [AndroidProcessTerminationConflictLogInstrumentedTest] to prove that a
 * persisted [io.dataloom.api.conflict.UnresolvedConflictRecord] survives a
 * genuine Android OS process kill and relaunch -- not a same-process
 * close/reopen simulation like
 * `RoomDurableStateStoreUnresolvedConflictIntegrationTest`'s own restart-proof
 * test already provides against a mocked DAO, or
 * `RoomDurableStateStoreInstrumentedTest`'s real-device close/reopen of the
 * same connection.
 *
 * Both entry points open a fresh [DataLoomRoomDatabase] connection to the
 * on-disk database named by the call argument and run the real
 * [DurableUnresolvedConflictLog]/[RoomDurableStateStore] production
 * persistence path -- never touching the underlying Room table directly.
 * Each call also reports [android.os.Process.myPid] so the caller can prove
 * two calls were served by two different OS processes, not a warm reused
 * one.
 */
public class ConflictLogProcessTerminationContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val databaseName = requireNotNull(arg) {
            "ConflictLogProcessTerminationContentProvider requires a database name argument."
        }
        val appContext = requireNotNull(context) {
            "ConflictLogProcessTerminationContentProvider has no attached Context."
        }
        return when (method) {
            ConflictLogProcessTerminationContract.METHOD_RECORD_CONFLICT -> {
                runBlocking { recordConflict(appContext, databaseName) }
            }
            ConflictLogProcessTerminationContract.METHOD_READ_CONFLICT -> {
                runBlocking { readConflict(appContext, databaseName) }
            }
            else -> error("Unknown ConflictLogProcessTerminationContentProvider method: $method")
        }
    }

    private suspend fun recordConflict(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val outcome = log(database).record(CONFLICT_ID, RECORD)
            val recorded = outcome as DurableUnresolvedConflictRecordOutcome.Recorded
            return recordBundle(recorded.record)
        } finally {
            database.close()
        }
    }

    private suspend fun readConflict(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val current = log(database).current(CONFLICT_ID)
            val result = current as ProviderOperationResult.Success<UnresolvedConflictRecord?>
            val record = result.value
            return if (record == null) {
                Bundle().apply {
                    putInt(ConflictLogProcessTerminationContract.KEY_PID, android.os.Process.myPid())
                    putString(ConflictLogProcessTerminationContract.KEY_STATUS, "MISSING")
                }
            } else {
                recordBundle(record)
            }
        } finally {
            database.close()
        }
    }

    private fun log(database: DataLoomRoomDatabase): DurableUnresolvedConflictLog {
        val store = RoomDurableStateStore(
            database,
            NAMESPACE,
            DurableUnresolvedConflictLog.KeyEncoder,
            UnresolvedConflictRecordCodec(),
        )
        return DurableUnresolvedConflictLog(store)
    }

    private fun recordBundle(record: UnresolvedConflictRecord): Bundle = Bundle().apply {
        putInt(ConflictLogProcessTerminationContract.KEY_PID, android.os.Process.myPid())
        putString(ConflictLogProcessTerminationContract.KEY_STATUS, "RECORDED")
        putString(ConflictLogProcessTerminationContract.KEY_CONFLICT_TYPE, record.conflictType.name)
        putString(ConflictLogProcessTerminationContract.KEY_ENTITY_TYPE, record.entity.type.value)
        putString(ConflictLogProcessTerminationContract.KEY_ENTITY_ID, record.entity.id.value)
        putString(
            ConflictLogProcessTerminationContract.KEY_LOCAL_CHANGE_EVENT_ID,
            record.localChange.changeEventId.value,
        )
        putString(
            ConflictLogProcessTerminationContract.KEY_REMOTE_CHANGE_EVENT_ID,
            record.remoteChange.changeEventId.value,
        )
        putString(ConflictLogProcessTerminationContract.KEY_REASON, record.reason.name)
        putLong(
            ConflictLogProcessTerminationContract.KEY_COMMITTED_AT_MILLIS,
            record.committedAt.epochMilliseconds,
        )
    }

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
        val CONFLICT_ID = ConflictId("conflict-proof-process-kill")
        val RECORD = UnresolvedConflictRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("note"), EntityId("note-process-kill-1")),
            localChange = UnresolvedConflictChangeSummary(
                ChangeEventId("local-process-kill-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.Empty,
            ),
            remoteChange = UnresolvedConflictChangeSummary(
                ChangeEventId("remote-process-kill-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.Empty,
            ),
            conflictMetadata = DataLoomMetadata.Empty,
            reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
            committedAt = DataLoomInstant(10_000L),
        )
    }
}
