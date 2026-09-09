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
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlinx.coroutines.runBlocking

/**
 * Test-only [ContentProvider] hosted in its own `:resolvedconflictproof`
 * process (see `src/androidTest/AndroidManifest.xml`), used exclusively by
 * [AndroidProcessTerminationResolvedConflictDecisionLogInstrumentedTest] to
 * prove that a persisted
 * [io.dataloom.api.conflict.ResolvedConflictDecisionRecord] survives a
 * genuine Android OS process kill and relaunch -- the same proof
 * [ConflictLogProcessTerminationContentProvider] already establishes for
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog], extended to this
 * gate's other durable conflict-engine domain, exactly as that test's own
 * class doc named as a real, narrow follow-up rather than silently claiming
 * it already covered.
 *
 * Both entry points open a fresh [DataLoomRoomDatabase] connection to the
 * on-disk database named by the call argument and run the real
 * [DurableResolvedConflictDecisionLog]/[RoomDurableStateStore] production
 * persistence path -- never touching the underlying Room table directly.
 * Each call also reports [android.os.Process.myPid] so the caller can prove
 * two calls were served by two different OS processes, not a warm reused
 * one.
 */
public class ResolvedConflictDecisionLogProcessTerminationContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val databaseName = requireNotNull(arg) {
            "ResolvedConflictDecisionLogProcessTerminationContentProvider requires a database name argument."
        }
        val appContext = requireNotNull(context) {
            "ResolvedConflictDecisionLogProcessTerminationContentProvider has no attached Context."
        }
        return when (method) {
            ResolvedConflictDecisionLogProcessTerminationContract.METHOD_RECORD_DECISION -> {
                runBlocking { recordDecision(appContext, databaseName) }
            }
            ResolvedConflictDecisionLogProcessTerminationContract.METHOD_READ_DECISION -> {
                runBlocking { readDecision(appContext, databaseName) }
            }
            else -> error("Unknown ResolvedConflictDecisionLogProcessTerminationContentProvider method: $method")
        }
    }

    private suspend fun recordDecision(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val outcome = log(database).record(CONFLICT_ID, RECORD)
            val recorded = outcome as DurableResolvedConflictDecisionRecordOutcome.Recorded
            return recordBundle(recorded.record)
        } finally {
            database.close()
        }
    }

    private suspend fun readDecision(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val current = log(database).current(CONFLICT_ID)
            val result = current as ProviderOperationResult.Success<ResolvedConflictDecisionRecord?>
            val record = result.value
            return if (record == null) {
                Bundle().apply {
                    putInt(ResolvedConflictDecisionLogProcessTerminationContract.KEY_PID, android.os.Process.myPid())
                    putString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_STATUS, "MISSING")
                }
            } else {
                recordBundle(record)
            }
        } finally {
            database.close()
        }
    }

    private fun log(database: DataLoomRoomDatabase): DurableResolvedConflictDecisionLog {
        val store = RoomDurableStateStore(
            database,
            NAMESPACE,
            DurableResolvedConflictDecisionLog.KeyEncoder,
            ResolvedConflictDecisionRecordCodec(),
        )
        return DurableResolvedConflictDecisionLog(store)
    }

    private fun recordBundle(record: ResolvedConflictDecisionRecord): Bundle = Bundle().apply {
        putInt(ResolvedConflictDecisionLogProcessTerminationContract.KEY_PID, android.os.Process.myPid())
        putString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_STATUS, "RECORDED")
        putString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_CONFLICT_TYPE, record.conflictType.name)
        putString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_ENTITY_TYPE, record.entity.type.value)
        putString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_ENTITY_ID, record.entity.id.value)
        putString(
            ResolvedConflictDecisionLogProcessTerminationContract.KEY_LOCAL_CHANGE_EVENT_ID,
            record.localChange.changeEventId.value,
        )
        putString(
            ResolvedConflictDecisionLogProcessTerminationContract.KEY_REMOTE_CHANGE_EVENT_ID,
            record.remoteChange.changeEventId.value,
        )
        putString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_RESOLVER_ID, record.resolverId.value)
        putString(ResolvedConflictDecisionLogProcessTerminationContract.KEY_DECISION_KIND, record.decisionKind.name)
        putLong(
            ResolvedConflictDecisionLogProcessTerminationContract.KEY_COMMITTED_AT_MILLIS,
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
        const val NAMESPACE: String = "resolved-conflict-decisions"
        val CONFLICT_ID = ConflictId("conflict-proof-process-kill-resolved")
        val RECORD = ResolvedConflictDecisionRecord(
            conflictType = ConflictType.CONCURRENT_CHANGE,
            entity = EntityReference(EntityType("note"), EntityId("note-process-kill-resolved-1")),
            localChange = UnresolvedConflictChangeSummary(
                ChangeEventId("local-process-kill-resolved-1"),
                ChangeOperation.UPDATE,
                DataLoomMetadata.Empty,
            ),
            remoteChange = UnresolvedConflictChangeSummary(
                ChangeEventId("remote-process-kill-resolved-1"),
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
