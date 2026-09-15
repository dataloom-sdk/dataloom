package io.dataloom.storage.sqldelight

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.payload.PayloadContentType
import io.dataloom.api.provider.ProviderCapability
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
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyReconciliationProvider
import io.dataloom.api.strategy.StrategyReconciliationRequest
import io.dataloom.api.strategy.StrategyReconciliationResult
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.storage.sqldelight.internal.SqlDelightStorageProviderError
import kotlin.coroutines.cancellation.CancellationException

/**
 * SQLDelight-backed reference [StorageProvider] implementation.
 *
 * This implementation persists outbound changes, inbound-applied changes, and
 * checkpoints to SQLDelight-managed SQLite tables. The class is thread-safe for
 * concurrent callers as long as the supplied SQLDelight driver is thread-safe
 * for the current platform.
 *
 * ## `readLocalConflictCandidate`
 *
 * Overrides the safe [StorageProvider.readLocalConflictCandidate] default,
 * mirroring `RoomStorageProvider`'s reference approach: considers only the
 * `outbound_changes` table, never `inbound_changes_applied`, since the
 * outbound table is this provider's own record of the local application's
 * pending or recently-made edits — exactly what a genuine local-vs-remote
 * conflict compares against. An entity with no outbound row has no local
 * edit to disagree with an incoming remote change, so an inbound-only-synced
 * entity correctly reports [LocalConflictCandidateReadResult.NotFound].
 *
 * Unlike Room, this provider's own [acknowledgeOutboundChanges] *deletes* the
 * row for an [io.dataloom.api.synchronization.ChangeAcknowledgementStatus.ACCEPTED]
 * event rather than retaining it — an existing, already-documented platform
 * difference this method does not paper over. In practice this means an
 * entity whose only outbound edit has already been accepted by the remote
 * also correctly reports `NotFound`: there is no still-outstanding local
 * edit left to compare, which is at least as defensible as Room's
 * retain-everything behavior for this narrow purpose. When multiple
 * outbound rows remain for the same entity, the highest `sequence` (the
 * most recently inserted) is returned.
 *
 * ## [StrategyLocalFallbackProvider]/[StrategyReconciliationProvider]
 *
 * Both are implemented as bounded, domain-agnostic existence checks over
 * this provider's own infrastructure tables — never entity payloads or
 * business merge decisions. See [evaluateLocalFallback] and
 * [reconcileStrategy] for the exact, narrow signal each reports.
 */
public class SqlDelightStorageProvider(
    private val storage: SqlDelightStorageDatabase,
    override val descriptor: ProviderDescriptor = defaultDescriptor(),
) : StorageProvider, StrategyLocalFallbackProvider, StrategyReconciliationProvider {
    private val queries = storage.database.dataLoomStorageQueries

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(
            ProviderHealth(status = ProviderHealthStatus.HEALTHY),
        )

    override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    /**
     * Persists outbound changes for later [readOutboundChanges] reads.
     *
     * This helper is intentionally application-agnostic and stores the exact
     * [ChangeSet] values without interpreting payload content.
     */
    public suspend fun persistOutboundChanges(
        changeSet: ChangeSet,
    ): ProviderOperationResult<Unit> = runStorageOperation {
        storage.database.transaction {
            val changeSetMetadata: String = encodeMetadata(changeSet.metadata)
            changeSet.events.forEach { event ->
                queries.insertOrReplaceOutboundChange(
                    event_id = event.id.value,
                    change_set_id = changeSet.id.value,
                    change_set_metadata = changeSetMetadata,
                    entity_type = event.entity.type.value,
                    entity_id = event.entity.id.value,
                    entity_version = event.entity.version?.value,
                    operation = event.operation.name,
                    payload_content_type = event.payload?.contentType?.value,
                    payload_bytes = event.payload?.copyBytes(),
                    event_metadata = encodeMetadata(event.metadata),
                    state = OutboundState.PENDING.value,
                )
            }
        }
    }

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> = runStorageOperation {
        val rows = queries.selectEligibleOutboundChanges(
            mapper = ::OutboundRow,
        ).executeAsList()
        val filteredRows = rows.filter { row ->
            request.entityTypes.isEmpty() || request.entityTypes.any { it.value == row.entityType }
        }

        val selectedRows: List<OutboundRow> =
            filteredRows
                .firstOrNull()
                ?.changeSetId
                ?.let { selectedChangeSetId -> filteredRows.filter { it.changeSetId == selectedChangeSetId } }
                .orEmpty()

        if (selectedRows.isEmpty()) {
            OutboundChangeReadResult.NoChanges
        } else {
            val maxEvents: Int = request.maxEvents ?: selectedRows.size
            val limitedRows = selectedRows.take(maxEvents)
            val hasMore: Boolean = filteredRows.size > limitedRows.size
            OutboundChangeReadResult.Changes(
                changeSet = limitedRows.toChangeSet(),
                hasMore = hasMore,
            )
        }
    }

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> = runStorageOperation {
        storage.database.transaction {
            val changeSetMetadata: String = encodeMetadata(request.changeSet.metadata)
            request.changeSet.events.forEach { event ->
                queries.insertOrReplaceInboundAppliedChange(
                    event_id = event.id.value,
                    change_set_id = request.changeSet.id.value,
                    change_set_metadata = changeSetMetadata,
                    entity_type = event.entity.type.value,
                    entity_id = event.entity.id.value,
                    entity_version = event.entity.version?.value,
                    operation = event.operation.name,
                    payload_content_type = event.payload?.contentType?.value,
                    payload_bytes = event.payload?.copyBytes(),
                    event_metadata = encodeMetadata(event.metadata),
                )
            }
        }
    }

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> = runStorageOperation {
        storage.database.transaction {
            request.acknowledgement.events.forEach { acknowledgement ->
                when (acknowledgement.status) {
                    ChangeAcknowledgementStatus.ACCEPTED -> {
                        queries.deleteOutboundByEventId(event_id = acknowledgement.eventId.value)
                    }

                    ChangeAcknowledgementStatus.RETRY -> {
                        queries.updateOutboundStateByEventId(
                            state = OutboundState.RETRY.value,
                            event_id = acknowledgement.eventId.value,
                        )
                    }

                    ChangeAcknowledgementStatus.REJECTED -> {
                        queries.updateOutboundStateByEventId(
                            state = OutboundState.REJECTED.value,
                            event_id = acknowledgement.eventId.value,
                        )
                    }
                }
            }
        }
    }

    override suspend fun readLocalConflictCandidate(
        request: LocalConflictCandidateReadRequest,
    ): ProviderOperationResult<LocalConflictCandidateReadResult> = runStorageOperation {
        val row = queries.selectLatestOutboundChangeForEntity(
            entity_type = request.entity.type.value,
            entity_id = request.entity.id.value,
            mapper = ::OutboundRow,
        ).executeAsOneOrNull()

        if (row == null) {
            LocalConflictCandidateReadResult.NotFound
        } else {
            LocalConflictCandidateReadResult.Found(row.toChangeEvent())
        }
    }

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> = runStorageOperation {
        queries.selectCheckpointByKey(
            checkpoint_key = request.key.value,
            mapper = ::CheckpointRow,
        ).executeAsOneOrNull()?.toCheckpoint()
    }

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> = runStorageOperation {
        storage.database.transaction {
            queries.upsertCheckpoint(
                checkpoint_key = request.checkpoint.key.value,
                checkpoint_token = request.checkpoint.token.value,
                checkpoint_metadata = encodeMetadata(request.checkpoint.metadata),
            )
        }
    }

    /**
     * Reports whether this provider currently holds synchronized local state,
     * independent of and never trusting [StrategyLocalFallbackRequest
     * .evaluatedCacheState] alone — that field reflects evidence captured at
     * strategy-evaluation time, which for a durably queued continuation may
     * be significantly stale by the time this method actually runs.
     *
     * The signal is a bounded existence check over this provider's own two
     * infrastructure tables: a checkpoint has been persisted by
     * [writeCheckpoint], or at least one inbound change set has been applied
     * by [applyInboundChanges]. Either is real evidence that a remote
     * synchronization has previously landed local state; neither reads
     * entity identifiers, payloads, or checkpoint tokens. When available,
     * [request]'s own [StrategyLocalFallbackRequest.evaluatedCacheState] is
     * echoed back if it is [StrategyCacheState.FRESH] or
     * [StrategyCacheState.STALE] (the only two states
     * [StrategyLocalFallbackResult.Available] accepts); otherwise
     * [StrategyCacheState.STALE] is reported, since a provider-level
     * existence check alone can never independently establish
     * [StrategyCacheState.FRESH] without an application-owned freshness
     * policy DataLoom does not own.
     */
    override suspend fun evaluateLocalFallback(
        request: StrategyLocalFallbackRequest,
    ): ProviderOperationResult<StrategyLocalFallbackResult> = runStorageOperation {
        val hasSynchronizedLocalState = queries.selectAnyCheckpointExists().executeAsOne() ||
            queries.selectAnyInboundAppliedExists().executeAsOne()
        if (hasSynchronizedLocalState) {
            val cacheState = request.evaluatedCacheState.takeIf {
                it == StrategyCacheState.FRESH || it == StrategyCacheState.STALE
            } ?: StrategyCacheState.STALE
            StrategyLocalFallbackResult.Available(cacheState)
        } else {
            StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING)
        }
    }

    /**
     * Bounded, domain-agnostic confirmation hook for the built-in `RECONCILE`
     * operation — never a merge or conflict-resolution pipeline.
     *
     * By the time `AcceptedStrategyPlanExecutionCoordinator` invokes this
     * method, every provider-level effect an accepted plan's `RECONCILE`
     * operation stands after — pushing or pulling remote changes, persisting
     * them, serving local state — has already completed through this
     * provider's own ordinary [readOutboundChanges]/[applyInboundChanges]/
     * [writeCheckpoint] methods earlier in the same execution. There is no
     * additional entity-level merge left for a generic, payload-free storage
     * provider to perform: [request] carries only bounded plan identity and a
     * [StrategyOperation] evidence list, deliberately no entity identifiers
     * or payloads, so any actual business-merge action here would mean
     * inventing rules DataLoom does not own (see
     * `docs/architecture/system-overview.md`'s "Product boundary").
     *
     * What this method does verify for real: when the completed evidence
     * includes [StrategyOperation.PERSIST_REMOTE] — meaning the plan's own
     * remote-persisting step should have just landed a checkpoint — it
     * confirms one now exists, returning [StrategyReconciliationResult
     * .Applied] when it does and a real [ProviderOperationResult.Failure]
     * when it unexpectedly does not (a genuine storage inconsistency worth
     * surfacing rather than silently swallowing). When the evidence has no
     * remote-persisting step (for example a pure `PUSH` continuation, which
     * has nothing checkpoint-shaped to confirm), reconciliation reports
     * [StrategyReconciliationResult.NotRequired] — existing state already
     * satisfies the accepted plan, exactly as that result's own contract
     * describes.
     */
    override suspend fun reconcileStrategy(
        request: StrategyReconciliationRequest,
    ): ProviderOperationResult<StrategyReconciliationResult> {
        if (StrategyOperation.PERSIST_REMOTE !in request.completedOperations) {
            return ProviderOperationResult.Success(StrategyReconciliationResult.NotRequired)
        }
        return try {
            if (queries.selectAnyCheckpointExists().executeAsOne()) {
                ProviderOperationResult.Success(StrategyReconciliationResult.Applied)
            } else {
                ProviderOperationResult.Failure(
                    SqlDelightStorageProviderError.reconciliationCheckpointMissing(),
                )
            }
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (throwable: Throwable) {
            ProviderOperationResult.Failure(
                SqlDelightStorageProviderError.databaseFailure(throwable),
            )
        }
    }

    private inline fun <T> runStorageOperation(
        block: () -> T,
    ): ProviderOperationResult<T> = try {
        ProviderOperationResult.Success(block())
    } catch (cancellationException: CancellationException) {
        throw cancellationException
    } catch (throwable: Throwable) {
        ProviderOperationResult.Failure(
            SqlDelightStorageProviderError.databaseFailure(throwable),
        )
    }

    private fun List<OutboundRow>.toChangeSet(): ChangeSet {
        val first = first()
        return ChangeSet(
            id = ChangeSetId(first.changeSetId),
            events = map { row -> row.toChangeEvent() },
            metadata = decodeMetadata(first.changeSetMetadata),
        )
    }

    private fun OutboundRow.toChangeEvent(): ChangeEvent = ChangeEvent(
        id = ChangeEventId(eventId),
        entity = EntityReference(
            type = EntityType(entityType),
            id = EntityId(entityId),
            version = entityVersion?.let(::EntityVersion),
        ),
        operation = ChangeOperation.valueOf(operation),
        payload = toPayload(),
        metadata = decodeMetadata(eventMetadata),
    )

    private fun OutboundRow.toPayload(): DataLoomPayload? {
        val contentType: String = payloadContentType ?: return null
        val bytes: ByteArray = payloadBytes ?: return null
        return DataLoomPayload(
            contentType = PayloadContentType(contentType),
            bytes = bytes,
        )
    }

    private fun CheckpointRow.toCheckpoint(): SynchronizationCheckpoint =
        SynchronizationCheckpoint(
            key = CheckpointKey(checkpointKey),
            token = CheckpointToken(checkpointToken),
            metadata = decodeMetadata(checkpointMetadata),
        )

    private enum class OutboundState(
        val value: String,
    ) {
        PENDING("PENDING"),
        RETRY("RETRY"),
        REJECTED("REJECTED"),
    }

    private companion object {
        private const val METADATA_ENTRY_SEPARATOR: String = "\u001F"
        private const val METADATA_KEY_VALUE_SEPARATOR: String = "\u001E"
        private const val ESCAPE: String = "\\"

        private fun encodeMetadata(metadata: DataLoomMetadata): String = metadata.entries.entries.joinToString(
            separator = METADATA_ENTRY_SEPARATOR,
        ) { (key, value) ->
            "${key.escapeMetadataComponent()}$METADATA_KEY_VALUE_SEPARATOR${value.escapeMetadataComponent()}"
        }

        private fun decodeMetadata(encoded: String): DataLoomMetadata {
            if (encoded.isEmpty()) {
                return DataLoomMetadata.Empty
            }

            val entries = linkedMapOf<String, String>()
            encoded.split(METADATA_ENTRY_SEPARATOR).forEach { encodedEntry ->
                if (encodedEntry.isEmpty()) {
                    return@forEach
                }
                val separatorIndex = encodedEntry.indexOf(METADATA_KEY_VALUE_SEPARATOR)
                if (separatorIndex < 0) {
                    return@forEach
                }
                val key = encodedEntry.substring(0, separatorIndex).unescapeMetadataComponent()
                val value = encodedEntry.substring(separatorIndex + METADATA_KEY_VALUE_SEPARATOR.length)
                    .unescapeMetadataComponent()
                if (key.isNotBlank()) {
                    entries[key] = value
                }
            }
            return DataLoomMetadata.of(entries)
        }

        private fun String.escapeMetadataComponent(): String =
            replace(ESCAPE, "$ESCAPE$ESCAPE")
                .replace(METADATA_ENTRY_SEPARATOR, "$ESCAPE\u001F")
                .replace(METADATA_KEY_VALUE_SEPARATOR, "$ESCAPE\u001E")

        private fun String.unescapeMetadataComponent(): String =
            replace("$ESCAPE$ESCAPE", ESCAPE)
                .replace("$ESCAPE\u001F", METADATA_ENTRY_SEPARATOR)
                .replace("$ESCAPE\u001E", METADATA_KEY_VALUE_SEPARATOR)

        private fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage.sqldelight.reference"),
            name = ProviderName("SQLDelight Storage Provider"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
            capabilities = setOf(
                ProviderCapability("sqldelight"),
                ProviderCapability("reference"),
                ProviderCapability("multiplatform"),
            ),
        )
    }

    private data class OutboundRow(
        val sequence: Long,
        val eventId: String,
        val changeSetId: String,
        val changeSetMetadata: String,
        val entityType: String,
        val entityId: String,
        val entityVersion: String?,
        val operation: String,
        val payloadContentType: String?,
        val payloadBytes: ByteArray?,
        val eventMetadata: String,
        val state: String,
    )

    private data class CheckpointRow(
        val checkpointKey: String,
        val checkpointToken: String,
        val checkpointMetadata: String,
    )
}
