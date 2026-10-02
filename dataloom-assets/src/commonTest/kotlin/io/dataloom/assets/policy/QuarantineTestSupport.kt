package io.dataloom.assets.policy

import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.ForeignError

/**
 * A deterministic [DataLoomClock] that always returns [instant]. A local,
 * trivial stand-in for `dataloom-testing`'s `FixedDataLoomClock`: this
 * module cannot depend on `dataloom-testing` (it depends on
 * `dataloom-runtime`, which depends back on `dataloom-assets`), so this test
 * support is its own copy, kept intentionally tiny.
 */
class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
    override fun now(): DataLoomInstant = instant
}

/**
 * In-memory [DurableStateStore] for [AssetContentQuarantineRecord] that, like
 * [io.dataloom.assets.EncodingDurableStateStore], keeps only the *encoded
 * payload string* produced by [AssetContentQuarantineRecordCodec], so a test
 * through it exercises the real persistence format and decode validation --
 * not just object identity -- and a fresh [DurableAssetContentQuarantineLog]
 * instance over the same backing [rows] genuinely simulates a process
 * restart.
 */
class EncodingQuarantineStateStore(
    private val codec: AssetContentQuarantineRecordCodec = AssetContentQuarantineRecordCodec(),
) : DurableStateStore<AssetTransferSessionId, AssetContentQuarantineRecord> {

    class Row(val payload: String, val version: Long, val schemaVersion: Int)

    /** Raw persisted rows keyed by encoded scope, exposed so tests can inspect or corrupt them directly. */
    val rows = mutableMapOf<String, Row>()

    var failNextLoads = 0
    var failNextCompareAndSets = 0
    var forcedConflicts = 0

    override suspend fun load(
        scope: AssetTransferSessionId,
    ): ProviderOperationResult<DurableStateLoadResult<AssetContentQuarantineRecord>> {
        if (failNextLoads > 0) {
            failNextLoads--
            return ProviderOperationResult.Failure(ForeignError(Recoverability.RECOVERABLE))
        }
        val row = rows[DurableAssetContentQuarantineLog.KeyEncoder.encode(scope)]
            ?: return ProviderOperationResult.Success(DurableStateLoadResult.Missing)
        val state = try {
            codec.decode(row.payload)
        } catch (malformed: IllegalArgumentException) {
            return ProviderOperationResult.Failure(ForeignError(Recoverability.NON_RECOVERABLE))
        }
        return ProviderOperationResult.Success(
            DurableStateLoadResult.Found(DurableStateRecord(state, row.version, row.schemaVersion)),
        )
    }

    override suspend fun compareAndSet(
        request: DurableStateCompareAndSetRequest<AssetTransferSessionId, AssetContentQuarantineRecord>,
    ): ProviderOperationResult<DurableStateCompareAndSetResult<AssetContentQuarantineRecord>> {
        if (failNextCompareAndSets > 0) {
            failNextCompareAndSets--
            return ProviderOperationResult.Failure(ForeignError(Recoverability.RECOVERABLE))
        }
        val key = DurableAssetContentQuarantineLog.KeyEncoder.encode(request.scope)
        val current = rows[key]
        if (forcedConflicts > 0 || current?.version != request.expectedVersion) {
            if (forcedConflicts > 0) forcedConflicts--
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current?.let { toRecord(it) }))
        }
        val next = Row(codec.encode(request.nextState), (current?.version ?: -1L) + 1L, request.nextSchemaVersion)
        rows[key] = next
        return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(toRecord(next)))
    }

    private fun toRecord(row: Row) = DurableStateRecord(codec.decode(row.payload), row.version, row.schemaVersion)
}
