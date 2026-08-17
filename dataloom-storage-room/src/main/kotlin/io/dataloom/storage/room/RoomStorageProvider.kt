package io.dataloom.storage.room

import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.storage.room.internal.CorruptStorageStateException
import io.dataloom.storage.room.internal.DataLoomStorageRoomDatabase
import io.dataloom.storage.room.internal.InboundApplyDisposition
import io.dataloom.storage.room.internal.StorageProviderError
import io.dataloom.storage.room.internal.toDomain
import io.dataloom.storage.room.internal.toEntity
import java.util.concurrent.CancellationException

/**
 * AndroidX Room-backed reference implementation of [StorageProvider].
 *
 * ## Thread safety
 *
 * This provider relies on Room's generated suspend support and transaction
 * executors. It does not select a dispatcher or shift the caller onto another
 * coroutine context.
 *
 * ## Persistence model
 *
 * Outbound change sets remain stored until explicitly acknowledged.
 * Accepted events are retained for inspection but are no longer eligible for
 * [readOutboundChanges]. Retry events remain eligible for later reads. Inbound
 * application persists opaque change sets and checkpoints in generic tables
 * without interpreting payload bytes.
 *
 * ## `readLocalConflictCandidate`
 *
 * Overrides the safe [StorageProvider.readLocalConflictCandidate] default —
 * this is the reference implementation demonstrating what a real provider
 * override looks like. Deliberately considers only the *outbound* change-event
 * log, never inbound: the outbound log is this provider's own record of the
 * local application's pending or recently-made edits, which is exactly what a
 * genuine local-vs-remote conflict compares against. An entity with no
 * outbound history has no local edit to disagree with an incoming remote
 * change, so an inbound-only-synced entity correctly reports
 * [io.dataloom.api.storage.LocalConflictCandidateReadResult.NotFound] — that
 * case is an ordinary apply, not a conflict. When multiple outbound events
 * exist for the same entity (across one or more change sets, any
 * acknowledgement status), the most recently appended one is returned,
 * ordered by change-set insertion order then in-set event order — the two
 * change-set/event tables have no shared wall-clock ordering, so "most
 * recently appended" (not "most recently acknowledged" or a timestamp) is the
 * only ordering this schema can support without inventing one.
 *
 * ## Cancellation
 *
 * `CancellationException` propagates normally through all operations.
 *
 * @param database the Room database instance. Hold it as an application-process
 *   singleton; [RoomStorageProvider] does not own the database lifecycle.
 */
public class RoomStorageProvider(
    private val database: DataLoomStorageRoomDatabase,
) : StorageProvider {
    private val outboundChangeDao by lazy { database.outboundChangeDao() }
    private val inboundChangeDao by lazy { database.inboundChangeDao() }
    private val checkpointDao by lazy { database.storageCheckpointDao() }

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.storage.room"),
        name = ProviderName("DataLoom Room Storage Provider"),
        type = ProviderType.STORAGE,
        version = ProviderVersion("1.0.0"),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> = executeDatabaseOperation {
        val persisted = outboundChangeDao.readNextBatch(
            entityTypes = request.entityTypes.mapTo(linkedSetOf<String>()) { it.value },
            maxEvents = request.maxEvents,
        ) ?: return@executeDatabaseOperation ProviderOperationResult.Success(
            OutboundChangeReadResult.NoChanges,
        )

        ProviderOperationResult.Success(
            OutboundChangeReadResult.Changes(
                changeSet = persisted.toDomain(),
                hasMore = persisted.hasMore,
            ),
        )
    }

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> = executeDatabaseOperation {
        when (inboundChangeDao.applyChangeSet(request.changeSet)) {
            InboundApplyDisposition.STORED -> ProviderOperationResult.Success(Unit)
            InboundApplyDisposition.CONFLICT ->
                ProviderOperationResult.Failure(StorageProviderError.inboundConflict())
        }
    }

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> = executeDatabaseOperation {
        if (outboundChangeDao.recordAcknowledgement(request.acknowledgement)) {
            ProviderOperationResult.Success(Unit)
        } else {
            ProviderOperationResult.Failure(StorageProviderError.acknowledgementTargetMissing())
        }
    }

    override suspend fun readLocalConflictCandidate(
        request: LocalConflictCandidateReadRequest,
    ): ProviderOperationResult<LocalConflictCandidateReadResult> = executeDatabaseOperation {
        val entity = outboundChangeDao.findLatestEventForEntity(
            entityType = request.entity.type.value,
            entityId = request.entity.id.value,
        )
        ProviderOperationResult.Success(
            if (entity == null) {
                LocalConflictCandidateReadResult.NotFound
            } else {
                LocalConflictCandidateReadResult.Found(entity.toDomain())
            },
        )
    }

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> = executeDatabaseOperation {
        ProviderOperationResult.Success(
            checkpointDao.findByKey(request.key.value)?.toDomain(),
        )
    }

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> = executeDatabaseOperation {
        checkpointDao.upsert(request.checkpoint.toEntity())
        ProviderOperationResult.Success(Unit)
    }

    private suspend fun <T> executeDatabaseOperation(
        operation: suspend () -> ProviderOperationResult<T>,
    ): ProviderOperationResult<T> = try {
        operation()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: CorruptStorageStateException) {
        ProviderOperationResult.Failure(StorageProviderError.stateCorrupt())
    } catch (_: Exception) {
        ProviderOperationResult.Failure(StorageProviderError.databaseFailure())
    }
}
