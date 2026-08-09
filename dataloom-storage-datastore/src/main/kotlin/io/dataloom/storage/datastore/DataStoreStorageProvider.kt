package io.dataloom.storage.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.payload.PayloadContentType
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.storage.datastore.internal.DataStoreStorageProviderError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Jetpack Preferences DataStore-backed [StorageProvider] reference implementation for Android.
 *
 * ## Use-case fit
 *
 * [DataStoreStorageProvider] is designed for **small, bounded key-value synchronization
 * state**: user settings, feature flags, small cached preferences, and similar data that
 * fits naturally within Jetpack DataStore's key-value model. It is **not** a substitute
 * for general-purpose, large-scale, or relationally structured synchronization data.
 *
 * For general-purpose sync data, use the Room-backed provider (`dataloom-queue-room`).
 * See GitHub issues #209 and #215.
 *
 * ## DataStore schema
 *
 * Each outbound change event is stored under a dedicated DataStore string preference key.
 * An ordered pending index maintains the sequence of events awaiting synchronization.
 *
 * | Purpose | DataStore key | Value format |
 * |---------|--------------|--------------|
 * | Outbound pending index | `dl.out.pending` | Newline-separated `changeSetId/eventId` entries |
 * | Outbound event record | `dl.out.ev.<eventId>` | Pipe-delimited compact record (see below) |
 * | Inbound applied record | `dl.in.<entityType>.<entityId>` | Pipe-delimited compact record |
 * | Checkpoint | `dl.ckpt.<checkpointKey>` | Opaque checkpoint token string |
 *
 * ### Compact event record format
 *
 * ```
 * v1|<changeSetId>|<eventId>|<entityType>|<entityId>|<entityVersion|NONE>|<operation>|<contentType|NONE>|<hexPayload|NONE>
 * ```
 *
 * Payload bytes are encoded as lowercase hexadecimal. The literal string `NONE` represents an
 * absent optional field.
 *
 * **Restriction**: change-set IDs, event IDs, entity types, entity IDs, entity versions,
 * operation names, and content-type strings must not contain the `|` character or newline
 * characters. Payload bytes are encoded as lowercase hexadecimal and are not subject to
 * this restriction.
 *
 * ## Outbound capacity limit
 *
 * At most [MAX_OUTBOUND_EVENTS] pending outbound events may be stored at one time.
 * Attempting to enqueue events that would exceed this limit results in
 * [ProviderOperationResult.Failure] with error code `DATASTORE_OUTBOUND_LIMIT_EXCEEDED`.
 * Silent degradation does not occur.
 *
 * This limit exists because DataStore is designed for small, bounded key-value data, not
 * high-volume change streams. For applications that generate many change events, use
 * `dataloom-queue-room` (issues #209 and #215).
 *
 * ## Inbound apply semantics
 *
 * [applyInboundChanges] stores the most recent inbound event per entity (identified by
 * entity type and entity ID). If multiple inbound events target the same entity, only the
 * last event in the change set is retained. This matches the key-value model of DataStore
 * and is suitable for settings, flags, and preference-like data where only the current
 * value matters.
 *
 * ## Thread safety
 *
 * All DataStore operations are suspend functions. DataStore itself is coroutine-safe and
 * serialises concurrent writes internally. No internal dispatcher is applied; the caller
 * is responsible for coroutine-context management.
 *
 * @param dataStore the Preferences DataStore instance managed by the host application.
 * @param descriptor provider descriptor exposed through [StorageProvider.descriptor].
 *   Defaults to [DataStoreStorageProvider.defaultDescriptor].
 */
public class DataStoreStorageProvider(
    private val dataStore: DataStore<Preferences>,
    override val descriptor: ProviderDescriptor = defaultDescriptor(),
) : StorageProvider {

    public companion object {

        /**
         * Maximum number of pending outbound events this provider will store at one time.
         *
         * Attempting to enqueue events that would exceed this limit returns
         * [ProviderOperationResult.Failure] with error code
         * `DATASTORE_OUTBOUND_LIMIT_EXCEEDED`.
         *
         * This limit reflects DataStore's suitability for small, bounded key-value
         * synchronization data. For higher volumes, use `dataloom-queue-room`.
         */
        public const val MAX_OUTBOUND_EVENTS: Int = 256

        /**
         * Returns the default [ProviderDescriptor] for a [DataStoreStorageProvider].
         */
        public fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("dataloom.storage.datastore"),
            name = ProviderName("DataStoreStorageProvider"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        private const val NONE = "NONE"
        private const val FIELD_SEPARATOR = "|"
        private const val INDEX_SEPARATOR = "/"
        private const val RECORD_FORMAT_VERSION = "v1"
    }

    // ── DataStore key factories ───────────────────────────────────────────────

    private val outboundPendingKey = stringPreferencesKey("dl.out.pending")

    private fun outboundEventKey(eventId: String) =
        stringPreferencesKey("dl.out.ev.$eventId")

    private fun inboundKey(entityType: String, entityId: String) =
        stringPreferencesKey("dl.in.$entityType.$entityId")

    private fun checkpointStoreKey(key: String) =
        stringPreferencesKey("dl.ckpt.$key")

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * No-op initialization. DataStore is ready-to-use without explicit setup.
     *
     * @param context unused initialization context.
     * @return [ProviderOperationResult.Success] unconditionally.
     */
    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    /**
     * Returns a [ProviderHealthStatus.HEALTHY] snapshot.
     *
     * No active DataStore read is performed. The snapshot represents a static
     * declaration of operational readiness.
     *
     * @return [ProviderOperationResult.Success] with a HEALTHY [ProviderHealth].
     */
    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    /**
     * No-op close. DataStore lifecycle is managed externally by the host application.
     *
     * @return [ProviderOperationResult.Success] unconditionally.
     */
    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    // ── Outbound queue ────────────────────────────────────────────────────────

    /**
     * Enqueues a [ChangeSet] for outbound synchronization.
     *
     * This method is provided for the host application to add outbound change events to
     * the DataStore-backed queue. It is not part of the [StorageProvider] contract, which
     * only reads, acknowledges, and checkpoints changes.
     *
     * Adding events that would bring the pending count above [MAX_OUTBOUND_EVENTS] results
     * in a [ProviderOperationResult.Failure] without modifying the queue. The capacity check
     * and the subsequent enqueue are not guaranteed to be atomic.
     *
     * @param changeSet the change set to enqueue for outbound synchronization.
     * @return [ProviderOperationResult.Success] when the change set is enqueued,
     *   [ProviderOperationResult.Failure] with code `DATASTORE_OUTBOUND_LIMIT_EXCEEDED` when
     *   the limit would be exceeded, or [ProviderOperationResult.Failure] with code
     *   `DATASTORE_IO_FAILURE` on an IO error.
     */
    public suspend fun enqueueOutboundChanges(
        changeSet: ChangeSet,
    ): ProviderOperationResult<Unit> {
        return try {
            val snapshot = dataStore.data.first()
            val currentPending = snapshot.parsePendingIndex()
            if (currentPending.size + changeSet.events.size > MAX_OUTBOUND_EVENTS) {
                return ProviderOperationResult.Failure(
                    DataStoreStorageProviderError.outboundLimitExceeded(
                        currentCount = currentPending.size,
                        requested = changeSet.events.size,
                        limit = MAX_OUTBOUND_EVENTS,
                    ),
                )
            }
            dataStore.edit { prefs ->
                for (event in changeSet.events) {
                    prefs[outboundEventKey(event.id.value)] = encodeEvent(changeSet.id.value, event)
                }
                val newEntries = changeSet.events.map { "${changeSet.id.value}$INDEX_SEPARATOR${it.id.value}" }
                val updatedIndex = (currentPending.map { "${it.first}$INDEX_SEPARATOR${it.second}" } + newEntries)
                    .joinToString("\n")
                prefs[outboundPendingKey] = updatedIndex
            }
            ProviderOperationResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ProviderOperationResult.Failure(DataStoreStorageProviderError.ioFailure(e))
        }
    }

    // ── StorageProvider contract ──────────────────────────────────────────────

    /**
     * Reads outbound synchronization change events from the DataStore-backed pending queue.
     *
     * Returns [OutboundChangeReadResult.NoChanges] when the pending queue is empty.
     * Returns [OutboundChangeReadResult.Changes] with a non-empty [ChangeSet] when pending
     * events are available. The returned [ChangeSet] uses the change-set ID of the first
     * pending entry as its identifier.
     *
     * When [OutboundChangeReadRequest.maxEvents] is supplied, at most that many events are
     * included in the result. [OutboundChangeReadResult.Changes.hasMore] is set to `true`
     * when additional pending events remain beyond the returned batch.
     *
     * This operation does not modify the pending queue. Acknowledgement must be performed
     * via [acknowledgeOutboundChanges].
     *
     * @param request read request with optional entity-type restriction and batch hint.
     * @return [ProviderOperationResult.Success] with an [OutboundChangeReadResult], or
     *   [ProviderOperationResult.Failure] with code `DATASTORE_IO_FAILURE` on an IO error.
     */
    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> {
        return try {
            val snapshot = dataStore.data.first()
            val pending = snapshot.parsePendingIndex()
            if (pending.isEmpty()) {
                return ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }

            val limit = request.maxEvents ?: pending.size
            val batch = pending.take(limit)
            val hasMore = pending.size > batch.size

            val events = batch.mapNotNull { (_, eventId) ->
                snapshot[outboundEventKey(eventId)]?.let { decodeEvent(it) }
            }

            if (events.isEmpty()) {
                return ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
            }

            val changeSetId = ChangeSetId(batch.first().first)
            val changeSet = ChangeSet(id = changeSetId, events = events)
            ProviderOperationResult.Success(
                OutboundChangeReadResult.Changes(changeSet = changeSet, hasMore = hasMore),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ProviderOperationResult.Failure(DataStoreStorageProviderError.ioFailure(e))
        }
    }

    /**
     * Applies inbound synchronization change events to DataStore.
     *
     * Each inbound [ChangeEvent] is stored as a DataStore preference keyed by entity type
     * and entity ID (`dl.in.<entityType>.<entityId>`). If multiple events in the
     * [InboundChangeApplyRequest.changeSet] target the same entity, only the last event for
     * that entity is retained, matching the DataStore key-value model.
     *
     * This method is suitable for applying the current value of user settings, feature
     * flags, or preference-like data received from a remote system. It is not suitable
     * for applying relationally structured or high-volume change streams.
     *
     * @param request apply request containing the inbound change set to apply.
     * @return [ProviderOperationResult.Success] when applied, or [ProviderOperationResult.Failure]
     *   with code `DATASTORE_IO_FAILURE` on an IO error.
     */
    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> {
        return try {
            dataStore.edit { prefs ->
                for (event in request.changeSet.events) {
                    prefs[inboundKey(event.entity.type.value, event.entity.id.value)] =
                        encodeEvent(request.changeSet.id.value, event)
                }
            }
            ProviderOperationResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ProviderOperationResult.Failure(DataStoreStorageProviderError.ioFailure(e))
        }
    }

    /**
     * Acknowledges outbound synchronization change events in the DataStore-backed queue.
     *
     * Acknowledgement semantics per event:
     * - [ChangeAcknowledgementStatus.ACCEPTED]: removed from the pending index and the event
     *   record is deleted.
     * - [ChangeAcknowledgementStatus.REJECTED]: removed from the pending index and the event
     *   record is deleted. The host application is responsible for any rejection inspection
     *   policy outside the DataStore.
     * - [ChangeAcknowledgementStatus.RETRY]: retained in the pending index for the next
     *   [readOutboundChanges] call.
     *
     * Events in the pending queue that are not present in the acknowledgement are retained.
     *
     * @param request acknowledgement request with per-event statuses.
     * @return [ProviderOperationResult.Success] when recorded, or [ProviderOperationResult.Failure]
     *   with code `DATASTORE_IO_FAILURE` on an IO error.
     */
    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> {
        return try {
            val acknowledgedIds = request.acknowledgement.events
                .associate { it.eventId.value to it.status }

            dataStore.edit { prefs ->
                val pending = prefs.parsePendingIndex()
                val updatedPending = pending.filter { (_, eventId) ->
                    when (acknowledgedIds[eventId]) {
                        ChangeAcknowledgementStatus.ACCEPTED,
                        ChangeAcknowledgementStatus.REJECTED,
                        -> {
                            prefs.remove(outboundEventKey(eventId))
                            false
                        }
                        ChangeAcknowledgementStatus.RETRY -> true
                        null -> true
                    }
                }
                if (updatedPending.isEmpty()) {
                    prefs.remove(outboundPendingKey)
                } else {
                    prefs[outboundPendingKey] = updatedPending
                        .joinToString("\n") { "${it.first}$INDEX_SEPARATOR${it.second}" }
                }
            }
            ProviderOperationResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ProviderOperationResult.Failure(DataStoreStorageProviderError.ioFailure(e))
        }
    }

    /**
     * Reads a previously stored [SynchronizationCheckpoint] from DataStore.
     *
     * Returns `null` when no checkpoint is stored for the requested key.
     *
     * @param request read request containing the checkpoint key.
     * @return [ProviderOperationResult.Success] with the stored [SynchronizationCheckpoint]
     *   or `null`, or [ProviderOperationResult.Failure] with code `DATASTORE_IO_FAILURE`
     *   on an IO error.
     */
    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> {
        return try {
            val snapshot = dataStore.data.first()
            val token = snapshot[checkpointStoreKey(request.key.value)]
            val checkpoint = token?.let {
                SynchronizationCheckpoint(key = request.key, token = CheckpointToken(it))
            }
            ProviderOperationResult.Success(checkpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ProviderOperationResult.Failure(DataStoreStorageProviderError.ioFailure(e))
        }
    }

    /**
     * Persists a [SynchronizationCheckpoint] to DataStore.
     *
     * An existing checkpoint for the same key is overwritten. The caller must not invoke
     * this operation until all inbound changes associated with the checkpoint have been
     * applied successfully.
     *
     * @param request write request containing the checkpoint to persist.
     * @return [ProviderOperationResult.Success] when persisted, or [ProviderOperationResult.Failure]
     *   with code `DATASTORE_IO_FAILURE` on an IO error.
     */
    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> {
        return try {
            dataStore.edit { prefs ->
                prefs[checkpointStoreKey(request.checkpoint.key.value)] = request.checkpoint.token.value
            }
            ProviderOperationResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ProviderOperationResult.Failure(DataStoreStorageProviderError.ioFailure(e))
        }
    }

    // ── Compact event encoding ────────────────────────────────────────────────

    /**
     * Encodes a [ChangeEvent] as a pipe-delimited compact string record suitable for
     * DataStore string preference storage.
     *
     * Format: `v1|<changeSetId>|<eventId>|<entityType>|<entityId>|<entityVersion|NONE>|<operation>|<contentType|NONE>|<hexPayload|NONE>`
     *
     * Payload bytes are encoded as lowercase hexadecimal.
     */
    private fun encodeEvent(changeSetId: String, event: ChangeEvent): String {
        val entityVersion = event.entity.version?.value ?: NONE
        val contentType = event.payload?.contentType?.value ?: NONE
        val hexPayload = event.payload?.let { bytesToHex(it.copyBytes()) } ?: NONE
        return buildString {
            append(RECORD_FORMAT_VERSION)
            append(FIELD_SEPARATOR); append(changeSetId)
            append(FIELD_SEPARATOR); append(event.id.value)
            append(FIELD_SEPARATOR); append(event.entity.type.value)
            append(FIELD_SEPARATOR); append(event.entity.id.value)
            append(FIELD_SEPARATOR); append(entityVersion)
            append(FIELD_SEPARATOR); append(event.operation.name)
            append(FIELD_SEPARATOR); append(contentType)
            append(FIELD_SEPARATOR); append(hexPayload)
        }
    }

    /**
     * Decodes a compact string record produced by [encodeEvent] back into a [ChangeEvent].
     *
     * Returns `null` when the record is malformed or does not match the expected format.
     * Callers should treat a `null` result as a non-fatal decode failure and skip the event.
     */
    private fun decodeEvent(record: String): ChangeEvent? {
        val parts = record.split(FIELD_SEPARATOR)
        if (parts.size < 9 || parts[0] != RECORD_FORMAT_VERSION) return null
        val eventId = parts[2].takeIf { it.isNotBlank() } ?: return null
        val entityType = parts[3].takeIf { it.isNotBlank() } ?: return null
        val entityId = parts[4].takeIf { it.isNotBlank() } ?: return null
        val entityVersion = parts[5].takeIf { it != NONE && it.isNotBlank() }
        val operation = ChangeOperation.entries.find { it.name == parts[6] } ?: return null
        val contentType = parts[7].takeIf { it != NONE && it.isNotBlank() }
        val hexPayload = parts[8].takeIf { it != NONE && it.isNotBlank() }

        val payload = if (contentType != null && hexPayload != null) {
            runCatching {
                DataLoomPayload(
                    contentType = PayloadContentType(contentType),
                    bytes = hexToBytes(hexPayload),
                )
            }.getOrNull()
        } else {
            null
        }

        return ChangeEvent(
            id = ChangeEventId(eventId),
            entity = EntityReference(
                type = EntityType(entityType),
                id = EntityId(entityId),
                version = entityVersion?.let { EntityVersion(it) },
            ),
            operation = operation,
            payload = payload,
        )
    }

    // ── Hex encoding utilities ────────────────────────────────────────────────

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    // ── Pending index parsing ─────────────────────────────────────────────────

    /**
     * Parses the outbound pending index from a [Preferences] snapshot into an ordered list
     * of (changeSetId, eventId) pairs.
     *
     * Blank lines and malformed entries are silently skipped.
     */
    private fun Preferences.parsePendingIndex(): List<Pair<String, String>> {
        val raw = this[outboundPendingKey] ?: return emptyList()
        return raw.split("\n").mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val slashIndex = line.indexOf(INDEX_SEPARATOR)
            if (slashIndex < 1 || slashIndex == line.length - 1) return@mapNotNull null
            line.substring(0, slashIndex) to line.substring(slashIndex + 1)
        }
    }

    /**
     * Parses the outbound pending index from a [MutablePreferences] instance.
     *
     * Delegates to the [Preferences] overload; available inside [DataStore.edit] blocks.
     */
    private fun MutablePreferences.parsePendingIndex(): List<Pair<String, String>> =
        (this as Preferences).parsePendingIndex()
}
