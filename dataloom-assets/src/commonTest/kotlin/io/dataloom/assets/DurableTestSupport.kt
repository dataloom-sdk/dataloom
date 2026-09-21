package io.dataloom.assets

import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import kotlinx.coroutines.yield

/** Stands in for the process dying: nothing in the engine or store catches it. */
class CrashException : RuntimeException("simulated process death")

/**
 * In-memory [DurableStateStore] that, like the real Room-backed store, keeps
 * only the *encoded payload string* produced by [AssetTransferSessionCodec],
 * so every test through it exercises the persistence format and decode
 * validation, not just object identity. Several [DurableAssetTransferSessionStore]
 * instances over one of these model several workers, or a restarted process,
 * sharing one database.
 *
 * Version numbering matches `RoomDurableStateStore`: a new row is version 0 and
 * each update adds one.
 */
class EncodingDurableStateStore(
    private val codec: AssetTransferSessionCodec = AssetTransferSessionCodec(),
) : DurableStateStore<AssetTransferSessionId, AssetTransferSession> {

    class Row(val payload: String, val version: Long, val schemaVersion: Int)

    /** Raw persisted rows keyed by encoded scope, exposed so tests can corrupt or inspect them. */
    val rows = mutableMapOf<String, Row>()

    /** When true, `load` suspends once after reading, letting another coroutine interleave before the caller writes. */
    var yieldAfterLoad = false

    var failNextLoads = 0
    var failNextCompareAndSets = 0

    /** The next N compare-and-sets report a conflict without writing. */
    var forcedConflicts = 0

    /** Throws [CrashException] on the Nth (1-based) compare-and-set from now, before writing. */
    var crashOnCompareAndSet: Int? = null

    var compareAndSetCalls = 0
    var conflictsReported = 0
    var updates = 0

    override suspend fun load(
        scope: AssetTransferSessionId,
    ): ProviderOperationResult<DurableStateLoadResult<AssetTransferSession>> {
        if (failNextLoads > 0) {
            failNextLoads--
            return ProviderOperationResult.Failure(ForeignError(Recoverability.RECOVERABLE))
        }
        val row = rows[DurableAssetTransferSessionStore.KeyEncoder.encode(scope)]
        if (yieldAfterLoad) yield()
        if (row == null) return ProviderOperationResult.Success(DurableStateLoadResult.Missing)
        val state = try {
            codec.decode(row.payload)
        } catch (e: IllegalArgumentException) {
            return ProviderOperationResult.Failure(ForeignError(Recoverability.NON_RECOVERABLE))
        }
        return ProviderOperationResult.Success(
            DurableStateLoadResult.Found(DurableStateRecord(state, row.version, row.schemaVersion)),
        )
    }

    override suspend fun compareAndSet(
        request: DurableStateCompareAndSetRequest<AssetTransferSessionId, AssetTransferSession>,
    ): ProviderOperationResult<DurableStateCompareAndSetResult<AssetTransferSession>> {
        compareAndSetCalls++
        crashOnCompareAndSet?.let { remaining ->
            if (remaining == 1) {
                crashOnCompareAndSet = null
                throw CrashException()
            }
            crashOnCompareAndSet = remaining - 1
        }
        if (failNextCompareAndSets > 0) {
            failNextCompareAndSets--
            return ProviderOperationResult.Failure(ForeignError(Recoverability.RECOVERABLE))
        }
        val key = DurableAssetTransferSessionStore.KeyEncoder.encode(request.scope)
        val current = rows[key]
        if (forcedConflicts > 0 || current?.version != request.expectedVersion) {
            if (forcedConflicts > 0) forcedConflicts--
            conflictsReported++
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current?.let { toRecord(it) }))
        }
        val next = Row(codec.encode(request.nextState), (current?.version ?: -1L) + 1L, request.nextSchemaVersion)
        rows[key] = next
        updates++
        return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(toRecord(next)))
    }

    private fun toRecord(row: Row) = DurableStateRecord(codec.decode(row.payload), row.version, row.schemaVersion)
}
