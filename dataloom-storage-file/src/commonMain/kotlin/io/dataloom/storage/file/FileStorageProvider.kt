package io.dataloom.storage.file

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.identifier.ChangeSetId
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
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.storage.file.internal.CheckpointSerializer
import io.dataloom.storage.file.internal.EventSerializer
import io.dataloom.storage.file.internal.FileStorageIoException
import io.dataloom.storage.file.internal.FileSystemFacade
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reference [StorageProvider] backed by plain files on the local filesystem.
 *
 * ## Intended use
 *
 * `FileStorageProvider` is a **reference implementation** suited to
 * low-volume scenarios, demonstrations, and getting-started integrations.
 * It is **not** designed for high-throughput or large-dataset production
 * workloads. For those, prefer the Room or SQLDelight providers which
 * offer indexed queries, proper transaction isolation, and WAL durability.
 *
 * ## How it works
 *
 * The provider maintains a directory tree under [baseDir]:
 *
 * ```
 * {baseDir}/
 *   outbound/
 *     {eventId}.evt       – one file per pending outbound change event
 *   outbound.idx          – ordered list of pending event IDs (one per line)
 *   rejected/
 *     {eventId}.evt       – REJECTED events (kept inspectable)
 *   inbound/
 *     {changeSetId}.batch – applied inbound change batches
 *   checkpoint/
 *     {encodedKey}.chk    – one file per checkpoint key
 * ```
 *
 * Writes use atomic rename (write-temp → rename) so a crash mid-write
 * cannot corrupt already-committed records. The outbound index is the
 * source of truth: an event file without an index entry is an orphan
 * (safe to ignore); a missing event file listed in the index is treated
 * as a bounded corruption (the event is skipped and the index is repaired
 * on the next write).
 *
 * ## Thread safety
 *
 * All operations are serialized by an in-process [Mutex]. Concurrent
 * access from multiple processes to the same [baseDir] is **not**
 * supported.
 *
 * ## Dependencies
 *
 * No third-party library beyond standard Kotlin/JVM or Kotlin/Native
 * file I/O APIs and `kotlinx.coroutines.core`.
 *
 * ## `readLocalConflictCandidate`
 *
 * Overrides the safe [StorageProvider.readLocalConflictCandidate] default,
 * following the same outbound-only principle `RoomStorageProvider` and
 * `SqlDelightStorageProvider` already established: only `outbound/` is
 * considered, never `inbound/`, since it is this provider's own record of
 * the local application's pending or recently-made edits — exactly what a
 * genuine local-vs-remote conflict compares against. An entity with no
 * outbound history correctly reports [LocalConflictCandidateReadResult.NotFound],
 * including one only ever synced via [applyInboundChanges].
 *
 * There is no per-entity index — [outbound.idx][FILE_INDEX] is the only
 * ordering this provider persists, so finding the latest match for one
 * entity means scanning it in order and keeping the last hit. This is a
 * deliberate, documented cost, not an oversight: consistent with this
 * class's own "reference implementation... not designed for high-throughput
 * or large-dataset production workloads" scope note above.
 *
 * `rejected/` is deliberately **not** consulted. An [ChangeAcknowledgementStatus.ACCEPTED]
 * event is removed from the index the same way `SqlDelightStorageProvider`
 * removes its row — so an entity whose only outbound edit was already
 * accepted also correctly reports `NotFound`, no still-outstanding local
 * edit left to compare. A [ChangeAcknowledgementStatus.REJECTED] event is
 * moved out of `outbound/` into `rejected/` with no ordering information
 * relative to it — there is no persisted evidence to say whether a rejected
 * event or some other outbound entry for the same entity is more recent, so
 * treating a rejected edit as "no longer live local intent to compare
 * against" avoids inventing an ordering this schema cannot actually support.
 *
 * @param baseDir absolute path to the directory used for persistent storage.
 *   The directory and its subdirectories are created lazily on first use.
 */
public class FileStorageProvider(
    baseDir: String,
    override val descriptor: ProviderDescriptor = defaultDescriptor(),
) : StorageProvider {

    private val fs = FileSystemFacade(baseDir)
    private val mutex = Mutex()

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = mutex.withLock {
        try {
            fs.ensureInitialized()
            ProviderOperationResult.Success(Unit)
        } catch (_: FileStorageIoException) {
            ProviderOperationResult.Failure(FileStorageErrors.initializationFailure())
        } catch (_: Exception) {
            ProviderOperationResult.Failure(FileStorageErrors.initializationFailure())
        }
    }

    override suspend fun health(): ProviderOperationResult<ProviderHealth> = mutex.withLock {
        try {
            if (fs.baseDirExists()) {
                ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))
            } else {
                ProviderOperationResult.Failure(FileStorageErrors.directoryUnavailable())
            }
        } catch (_: Exception) {
            ProviderOperationResult.Failure(FileStorageErrors.directoryUnavailable())
        }
    }

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    // ─── Outbound ────────────────────────────────────────────────────────────

    /**
     * Stores an outbound [ChangeSet] so it becomes visible to the DataLoom
     * runtime via [readOutboundChanges].
     *
     * This is the **application-side** entry point for registering outbound
     * changes. Call this whenever the application makes a domain change that
     * should be pushed to the remote.
     *
     * The operation is atomic: all events in [changeSet] are written before
     * the index is updated, so a crash mid-way leaves the index unchanged.
     *
     * @param changeSet the outbound change set to persist.
     * @return [ProviderOperationResult.Success] on success, or
     *   [ProviderOperationResult.Failure] with a canonical error.
     */
    public suspend fun storeOutboundChangeSet(
        changeSet: ChangeSet,
    ): ProviderOperationResult<Unit> = mutex.withLock {
        try {
            fs.ensureInitialized()
            val currentIds = readIndex()
            // Write each event file first; don't update index until all are written.
            for (event in changeSet.events) {
                val content = EventSerializer.serialize(changeSet.id.value, event)
                fs.writeTextFileAtomically("${DIR_OUTBOUND}/${event.id.value}${EXT_EVENT}", content)
            }
            // Append new event IDs to the index atomically.
            val newIds = currentIds + changeSet.events.map { it.id.value }
            writeIndex(newIds)
            ProviderOperationResult.Success(Unit)
        } catch (_: FileStorageIoException) {
            ProviderOperationResult.Failure(FileStorageErrors.storeEventFailure())
        } catch (_: Exception) {
            ProviderOperationResult.Failure(FileStorageErrors.storeEventFailure())
        }
    }

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> = mutex.withLock {
        try {
            fs.ensureInitialized()
            val allIds = readIndex()
            if (allIds.isEmpty()) {
                return@withLock ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }

            val entityFilter = request.entityTypes
            val events = mutableListOf<ChangeEvent>()
            val orphanedIds = mutableListOf<String>()
            val maxEvents = request.maxEvents

            // Read events in index order, applying maxEvents and entity-type filters.
            // firstChangeSetId reflects the changeSetId of the first event that is
            // actually included in the returned batch (post entity-filter).
            var firstChangeSetId: String? = null

            val iter = allIds.listIterator()
            while (iter.hasNext()) {
                val eventId = iter.next()
                val relativePath = "${DIR_OUTBOUND}/${eventId}${EXT_EVENT}"
                val text = fs.readTextFile(relativePath)
                if (text == null) {
                    // Event file missing despite being in the index — bounded corruption.
                    // Record it so we can repair the index on the next write.
                    orphanedIds += eventId
                    continue
                }
                val (csId, event) = EventSerializer.deserialize(text) ?: run {
                    orphanedIds += eventId
                    continue
                }

                if (entityFilter.isNotEmpty() && event.entity.type !in entityFilter) continue

                events += event
                if (firstChangeSetId == null) firstChangeSetId = csId

                if (maxEvents != null && events.size >= maxEvents) {
                    // Batch is full. hasMore is true when there are remaining index entries.
                    // Callers must tolerate hasMore=true followed by NoChanges (e.g. remaining
                    // entries are all filtered by entityTypes on the next call).
                    val hasMore = iter.hasNext()
                    if (orphanedIds.isNotEmpty()) {
                        val orphanedSet = orphanedIds.toSet()
                        writeIndex(allIds.filter { it !in orphanedSet })
                    }
                    val cs = buildChangeSet(events, firstChangeSetId!!)
                    return@withLock ProviderOperationResult.Success(
                        OutboundChangeReadResult.Changes(changeSet = cs, hasMore = hasMore),
                    )
                }
            }

            if (orphanedIds.isNotEmpty()) {
                // Repair the index by removing orphaned entries.
                val orphanedSet = orphanedIds.toSet()
                val repairedIds = allIds.filter { it !in orphanedSet }
                writeIndex(repairedIds)
            }

            if (events.isEmpty()) {
                return@withLock ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }

            val cs = buildChangeSet(events, firstChangeSetId!!)
            ProviderOperationResult.Success(
                OutboundChangeReadResult.Changes(changeSet = cs, hasMore = false),
            )
        } catch (_: FileStorageIoException) {
            ProviderOperationResult.Failure(FileStorageErrors.readFailure())
        } catch (_: Exception) {
            ProviderOperationResult.Failure(FileStorageErrors.readFailure())
        }
    }

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> = mutex.withLock {
        try {
            fs.ensureInitialized()
            val ack = request.acknowledgement
            val toRemove = mutableSetOf<String>()

            for (eventAck in ack.events) {
                val eventId = eventAck.eventId.value
                val eventRelative = "${DIR_OUTBOUND}/${eventId}${EXT_EVENT}"
                when (eventAck.status) {
                    ChangeAcknowledgementStatus.ACCEPTED -> {
                        // Remove from outbound — will be pruned from index below.
                        fs.deleteFile(eventRelative)
                        toRemove += eventId
                    }
                    ChangeAcknowledgementStatus.RETRY -> {
                        // Leave event in place for the next read cycle.
                    }
                    ChangeAcknowledgementStatus.REJECTED -> {
                        // Move to rejected/ for application inspection.
                        val rejectedRelative = "${DIR_REJECTED}/${eventId}${EXT_EVENT}"
                        try {
                            fs.moveFileAtomically(eventRelative, rejectedRelative)
                        } catch (_: FileStorageIoException) {
                            // If the move fails because the source is missing, ignore.
                        }
                        toRemove += eventId
                    }
                }
            }

            if (toRemove.isNotEmpty()) {
                val currentIds = readIndex()
                val updatedIds = currentIds.filter { it !in toRemove }
                writeIndex(updatedIds)
            }

            ProviderOperationResult.Success(Unit)
        } catch (_: FileStorageIoException) {
            ProviderOperationResult.Failure(FileStorageErrors.acknowledgeFailure())
        } catch (_: Exception) {
            ProviderOperationResult.Failure(FileStorageErrors.acknowledgeFailure())
        }
    }

    override suspend fun readLocalConflictCandidate(
        request: LocalConflictCandidateReadRequest,
    ): ProviderOperationResult<LocalConflictCandidateReadResult> = mutex.withLock {
        try {
            fs.ensureInitialized()
            var latestMatch: ChangeEvent? = null
            for (eventId in readIndex()) {
                val text = fs.readTextFile("${DIR_OUTBOUND}/${eventId}${EXT_EVENT}") ?: continue
                val (_, event) = EventSerializer.deserialize(text) ?: continue
                if (event.entity.type == request.entity.type && event.entity.id == request.entity.id) {
                    latestMatch = event
                }
            }
            ProviderOperationResult.Success(
                if (latestMatch == null) {
                    LocalConflictCandidateReadResult.NotFound
                } else {
                    LocalConflictCandidateReadResult.Found(latestMatch)
                },
            )
        } catch (_: FileStorageIoException) {
            ProviderOperationResult.Failure(FileStorageErrors.readFailure())
        } catch (_: Exception) {
            ProviderOperationResult.Failure(FileStorageErrors.readFailure())
        }
    }

    // ─── Inbound ─────────────────────────────────────────────────────────────

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> = mutex.withLock {
        try {
            fs.ensureInitialized()
            val csId = request.changeSet.id.value
            val content = buildString {
                appendLine("DATALOOM_BATCH_V1")
                appendLine("changeSetId=${csId}")
                for (event in request.changeSet.events) {
                    appendLine("---")
                    append(EventSerializer.serialize(csId, event))
                }
            }
            fs.writeTextFileAtomically("${DIR_INBOUND}/${csId}${EXT_BATCH}", content)
            ProviderOperationResult.Success(Unit)
        } catch (_: FileStorageIoException) {
            ProviderOperationResult.Failure(FileStorageErrors.writeFailure())
        } catch (_: Exception) {
            ProviderOperationResult.Failure(FileStorageErrors.writeFailure())
        }
    }

    // ─── Checkpoint ──────────────────────────────────────────────────────────

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> = mutex.withLock {
        try {
            fs.ensureInitialized()
            val fileName = CheckpointSerializer.fileNameFor(request.key)
            val text = fs.readTextFile("${DIR_CHECKPOINT}/${fileName}")
            val checkpoint = text?.let { CheckpointSerializer.deserialize(it) }
            ProviderOperationResult.Success(checkpoint)
        } catch (_: FileStorageIoException) {
            ProviderOperationResult.Failure(FileStorageErrors.checkpointReadFailure())
        } catch (_: Exception) {
            ProviderOperationResult.Failure(FileStorageErrors.checkpointReadFailure())
        }
    }

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> = mutex.withLock {
        try {
            fs.ensureInitialized()
            val fileName = CheckpointSerializer.fileNameFor(request.checkpoint.key)
            val content = CheckpointSerializer.serialize(request.checkpoint)
            fs.writeTextFileAtomically("${DIR_CHECKPOINT}/${fileName}", content)
            ProviderOperationResult.Success(Unit)
        } catch (_: FileStorageIoException) {
            ProviderOperationResult.Failure(FileStorageErrors.checkpointWriteFailure())
        } catch (_: Exception) {
            ProviderOperationResult.Failure(FileStorageErrors.checkpointWriteFailure())
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun readIndex(): List<String> {
        val text = fs.readTextFile(FILE_INDEX) ?: return emptyList()
        return text.lineSequence()
            .filter { it.isNotBlank() && it != INDEX_HEADER }
            .toList()
    }

    private fun writeIndex(ids: List<String>) {
        val content = buildString {
            appendLine(INDEX_HEADER)
            for (id in ids) appendLine(id)
        }
        fs.writeTextFileAtomically(FILE_INDEX, content)
    }

    private fun buildChangeSet(events: List<ChangeEvent>, changeSetId: String): ChangeSet =
        ChangeSet(id = ChangeSetId(changeSetId), events = events)

    // ─── Constants ───────────────────────────────────────────────────────────

    internal companion object {
        internal const val DIR_OUTBOUND = "outbound"
        internal const val DIR_REJECTED = "rejected"
        internal const val DIR_INBOUND = "inbound"
        internal const val DIR_CHECKPOINT = "checkpoint"
        internal const val FILE_INDEX = "outbound.idx"
        internal const val INDEX_HEADER = "DATALOOM_OUTBOUND_IDX_V1"
        internal const val EXT_EVENT = ".evt"
        internal const val EXT_BATCH = ".batch"
    }
}

private fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId("io.dataloom.storage.file"),
    name = ProviderName("DataLoom File Storage Provider"),
    type = ProviderType.STORAGE,
    version = ProviderVersion("1.0.0"),
)
