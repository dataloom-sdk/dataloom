package io.dataloom.assets.policy

import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetTransferDirection
import io.dataloom.assets.AssetTransferSessionId

/** Whether a quarantined chunk's record is still held or has been released. */
public enum class AssetContentQuarantineStatus {
    /** Held pending review; nothing yet decided it is safe to release. */
    HELD,

    /** An authorized operator released it; see [AssetContentQuarantineRecord.release]. */
    RELEASED,
}

/**
 * Durable evidence of the operator action that released a quarantined chunk
 * from [AssetContentQuarantineStatus.HELD]. Written atomically with the
 * release itself (see [DurableAssetContentQuarantineLog.release]), so there
 * is no window in which a record reads as released without this evidence.
 *
 * This type does not decide *what* release means for the asset itself (a
 * host may choose to re-attempt the transfer under a new session id, or
 * simply record that a human cleared a false positive) -- it is audit
 * evidence of the decision, mirroring
 * [io.dataloom.api.conflict.ConflictQuarantineRelease]'s shape and purpose
 * for the conflict-quarantine domain.
 *
 * @param principal who (or what service account) made the release decision.
 * @param reason free-text justification, bounded the same as
 *   [AssetContentQuarantineRecord.reasonCode].
 * @param releasedAt when the release was recorded.
 */
public data class AssetContentQuarantineRelease(
    public val principal: String,
    public val reason: String,
    public val releasedAt: DataLoomInstant,
) {
    init {
        require(principal.isNotBlank() && principal.length <= AssetContentQuarantineRecord.MAX_TEXT_FIELD_LENGTH) {
            "AssetContentQuarantineRelease principal must be non-blank and at most " +
                "${AssetContentQuarantineRecord.MAX_TEXT_FIELD_LENGTH} characters."
        }
        require(reason.isNotBlank() && reason.length <= AssetContentQuarantineRecord.MAX_TEXT_FIELD_LENGTH) {
            "AssetContentQuarantineRelease reason must be non-blank and at most " +
                "${AssetContentQuarantineRecord.MAX_TEXT_FIELD_LENGTH} characters."
        }
    }
}

/**
 * Durable `TState` persisted per [AssetTransferSessionId] by
 * [DurableAssetContentQuarantineLog] -- one record for the chunk whose
 * [io.dataloom.assets.AssetContentPolicyDecision.Quarantine] decision failed
 * that session. Deliberately holds no payload bytes: a reviewer inspects
 * [matchedDigestHex]/[reasonCode] against their own source-of-truth (the same
 * deny-list a [HashDenyListAssetContentPolicy] was configured with, or
 * whatever produced the match), never bytes recovered from this log.
 *
 * A session is quarantined at most once (the engine fails it terminally on
 * the first [io.dataloom.assets.AssetContentPolicyDecision.Quarantine]), so
 * one record per [AssetTransferSessionId] is the whole domain -- no counting
 * or windowing like [io.dataloom.api.conflict.DurableConflictQuarantineLog]
 * needs, since a terminal session is never retried under the same id.
 *
 * @param sessionId the quarantined transfer session, repeated here (even
 *   though it is also the store scope) so a record read back is
 *   self-describing without the caller supplying the scope separately.
 * @param assetId the asset being transferred.
 * @param version the asset version being transferred.
 * @param mediaType the asset's declared media type.
 * @param direction upload or download.
 * @param chunkIndex the chunk whose content matched.
 * @param reasonCode the policy's own reason code (for example a deny-list
 *   entry's label), bounded exactly as
 *   [io.dataloom.assets.AssetContentPolicyDecision.Quarantine.reasonCode] is.
 * @param matchedDigestHex lowercase hex digest of the chunk that matched --
 *   the evidence a reviewer checks against the deny-list, never the chunk's
 *   actual bytes.
 * @param quarantinedAt when this record was written.
 * @param status [AssetContentQuarantineStatus.HELD] until [release].
 * @param release present exactly when [status] is
 *   [AssetContentQuarantineStatus.RELEASED].
 */
public data class AssetContentQuarantineRecord(
    public val sessionId: AssetTransferSessionId,
    public val assetId: AssetId,
    public val version: Long,
    public val mediaType: AssetMediaType,
    public val direction: AssetTransferDirection,
    public val chunkIndex: Int,
    public val reasonCode: String,
    public val matchedDigestHex: String,
    public val quarantinedAt: DataLoomInstant,
    public val status: AssetContentQuarantineStatus = AssetContentQuarantineStatus.HELD,
    public val release: AssetContentQuarantineRelease? = null,
) {
    init {
        require(version >= 1L) { "AssetContentQuarantineRecord version must be at least 1, but was $version." }
        require(chunkIndex >= 0) { "AssetContentQuarantineRecord chunkIndex must not be negative, but was $chunkIndex." }
        require(reasonCode.isNotBlank() && reasonCode.length <= MAX_TEXT_FIELD_LENGTH) {
            "AssetContentQuarantineRecord reasonCode must be non-blank and at most $MAX_TEXT_FIELD_LENGTH characters."
        }
        require(matchedDigestHex.isNotBlank() && matchedDigestHex.length <= MAX_TEXT_FIELD_LENGTH) {
            "AssetContentQuarantineRecord matchedDigestHex must be non-blank and at most $MAX_TEXT_FIELD_LENGTH characters."
        }
        require((status == AssetContentQuarantineStatus.RELEASED) == (release != null)) {
            "AssetContentQuarantineRecord release must be present exactly when status is RELEASED."
        }
    }

    public companion object {
        /** Upper bound on [reasonCode], [matchedDigestHex] and [AssetContentQuarantineRelease] text fields. */
        public const val MAX_TEXT_FIELD_LENGTH: Int = 256
    }
}

/** Outcome of one [DurableAssetContentQuarantineLog.record] call. */
public sealed interface AssetContentQuarantineRecordOutcome {

    /** [record] is now the persisted quarantine record for its session. */
    public data class Recorded(public val record: AssetContentQuarantineRecord) : AssetContentQuarantineRecordOutcome

    /**
     * A record already exists for this session -- either an idempotent
     * replay of the identical quarantine event (a crash between the
     * engine's decision and this write, then a retried `evaluate` call for
     * the same chunk), or, since a session is quarantined at most once ever,
     * a caller programming error; either way the first-written record wins
     * and nothing new is persisted.
     */
    public data class AlreadyRecorded(public val record: AssetContentQuarantineRecord) : AssetContentQuarantineRecordOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : AssetContentQuarantineRecordOutcome

    /** Every bounded compare-and-set attempt lost to a concurrent writer. */
    public data object ContentionLimitReached : AssetContentQuarantineRecordOutcome
}

/** Outcome of one [DurableAssetContentQuarantineLog.release] call. */
public sealed interface AssetContentQuarantineReleaseOutcome {

    /** The record was held and is now released. */
    public data class Released(public val record: AssetContentQuarantineRecord) : AssetContentQuarantineReleaseOutcome

    /**
     * The record was already released. [record] is the existing record,
     * whether or not [AssetContentQuarantineRelease] matches the one already
     * stored -- a quarantine record releases at most once; nothing is
     * persisted either way.
     */
    public data class AlreadyReleased(public val record: AssetContentQuarantineRecord) : AssetContentQuarantineReleaseOutcome

    /** No record is held for this session (none exists, or it was never quarantined). Nothing was persisted. */
    public data class NotHeld(public val record: AssetContentQuarantineRecord?) : AssetContentQuarantineReleaseOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : AssetContentQuarantineReleaseOutcome

    /** Every bounded compare-and-set attempt lost to a concurrent writer. */
    public data object ContentionLimitReached : AssetContentQuarantineReleaseOutcome
}

/**
 * Durable store for [io.dataloom.assets.AssetContentPolicyDecision.Quarantine]
 * decisions, backed by a [DurableStateStore], so a quarantined asset is not
 * just dropped silently -- its metadata is recorded somewhere a host can
 * later list, inspect and (via [release]) clear.
 *
 * ## Why this exists
 *
 * [io.dataloom.assets.AssetContentPolicy]'s own class doc deliberately leaves
 * "a durable quarantine store" and "re-admitting a quarantined asset later"
 * out of scope for that SPI slice -- those are host/application concerns
 * built on top of the decision point. This is a reference answer to that
 * deferred scope: the same `DurableStateStore` CAS-log pattern this codebase
 * already uses for
 * [io.dataloom.api.conflict.DurableConflictQuarantineLog] (conflict loops)
 * and [io.dataloom.assets.DurableAssetTransferSessionStore] (transfer
 * sessions), adopted for one more domain rather than inventing a new one.
 *
 * ## Scope and state
 *
 * `TScope` is [AssetTransferSessionId], reused directly (not wrapped) --
 * the same "reuse the domain's own identity type" precedent
 * [io.dataloom.api.asset.DurableAssetManifestHistory] (`AssetId`) and
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog] (`ConflictId`)
 * already establish. `TState` is [AssetContentQuarantineRecord]; it never
 * holds chunk bytes.
 *
 * ## Concurrency
 *
 * [record] and [release] are each a bounded load-evaluate-compare-and-set
 * loop, so two callers racing to record or release the same session each
 * land exactly once.
 *
 * @param store durable persistence for this log's [AssetContentQuarantineRecord].
 * @param schemaVersion the [DurableStateRecord.schemaVersion] this instance writes.
 * @param maximumStateUpdateAttempts bounded compare-and-set attempts per call. Must be at least `1`.
 */
public class DurableAssetContentQuarantineLog(
    private val store: DurableStateStore<AssetTransferSessionId, AssetContentQuarantineRecord>,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
    }

    /** The current record for [sessionId], or `null` if nothing was ever recorded. */
    public suspend fun current(sessionId: AssetTransferSessionId): ProviderOperationResult<AssetContentQuarantineRecord?> =
        when (val loaded = store.load(sessionId)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                (loaded.value as? DurableStateLoadResult.Found)?.record?.state,
            )
        }

    /**
     * Durably records [record] for its [AssetContentQuarantineRecord.sessionId],
     * the storage half of a [io.dataloom.assets.AssetContentPolicyDecision.Quarantine]
     * decision. [record] must already be [AssetContentQuarantineStatus.HELD]
     * (fresh, un-released) -- this is how a new quarantine is first recorded,
     * not how one is changed.
     */
    public suspend fun record(record: AssetContentQuarantineRecord): AssetContentQuarantineRecordOutcome {
        require(record.status == AssetContentQuarantineStatus.HELD) {
            "DurableAssetContentQuarantineLog.record requires a fresh HELD record; use release() to change one."
        }
        val scope = record.sessionId
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(scope)) {
                is ProviderOperationResult.Failure -> return AssetContentQuarantineRecordOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val found = (loaded as? DurableStateLoadResult.Found)?.record
            if (found != null) {
                // A session quarantines at most once; a second call for the same
                // session is either a replay (same evidence) or a caller error.
                // Either way the first write wins -- see AlreadyRecorded's doc.
                return AssetContentQuarantineRecordOutcome.AlreadyRecorded(found.state)
            }
            val update = store.compareAndSet(
                DurableStateCompareAndSetRequest(
                    scope = scope,
                    expectedVersion = null,
                    nextState = record,
                    nextSchemaVersion = schemaVersion,
                ),
            )
            when (update) {
                is ProviderOperationResult.Failure -> return AssetContentQuarantineRecordOutcome.PersistenceFailure(update.error)
                is ProviderOperationResult.Success -> when (val result = update.value) {
                    is DurableStateCompareAndSetResult.Updated -> return AssetContentQuarantineRecordOutcome.Recorded(result.record.state)
                    // Lost the race: reload and resolve as AlreadyRecorded against whatever won.
                    is DurableStateCompareAndSetResult.Conflict -> Unit
                }
            }
        }
        return AssetContentQuarantineRecordOutcome.ContentionLimitReached
    }

    /**
     * Releases the record held for [sessionId], recording [release] as
     * evidence. Idempotent: releasing an already-released record persists
     * nothing and returns [AssetContentQuarantineReleaseOutcome.AlreadyReleased].
     */
    public suspend fun release(
        sessionId: AssetTransferSessionId,
        release: AssetContentQuarantineRelease,
    ): AssetContentQuarantineReleaseOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(sessionId)) {
                is ProviderOperationResult.Failure -> return AssetContentQuarantineReleaseOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val found = (loaded as? DurableStateLoadResult.Found)?.record
            val existing = found?.state
            if (existing == null) {
                return AssetContentQuarantineReleaseOutcome.NotHeld(null)
            }
            if (existing.status == AssetContentQuarantineStatus.RELEASED) {
                return AssetContentQuarantineReleaseOutcome.AlreadyReleased(existing)
            }

            val next = existing.copy(status = AssetContentQuarantineStatus.RELEASED, release = release)
            val update = store.compareAndSet(
                DurableStateCompareAndSetRequest(
                    scope = sessionId,
                    expectedVersion = found.version,
                    nextState = next,
                    nextSchemaVersion = schemaVersion,
                ),
            )
            when (update) {
                is ProviderOperationResult.Failure -> return AssetContentQuarantineReleaseOutcome.PersistenceFailure(update.error)
                is ProviderOperationResult.Success -> when (val result = update.value) {
                    is DurableStateCompareAndSetResult.Updated -> return AssetContentQuarantineReleaseOutcome.Released(result.record.state)
                    is DurableStateCompareAndSetResult.Conflict -> Unit
                }
            }
        }
        return AssetContentQuarantineReleaseOutcome.ContentionLimitReached
    }

    public companion object {
        private const val DEFAULT_SCHEMA_VERSION: Int = 1
        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8

        /**
         * Reference [DurableStateScopeKeyEncoder]: the session id's own
         * string value, the same "already a bounded, unique string" reuse
         * [io.dataloom.assets.DurableAssetTransferSessionStore.KeyEncoder]
         * uses for the same scope type. A different stable, unique
         * `namespace` passed to the backing store (see `RoomDurableStateStore`)
         * is what keeps this domain's rows from colliding with that one's,
         * since both key encoders produce the same string for the same id.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<AssetTransferSessionId> =
            DurableStateScopeKeyEncoder { it.value }
    }
}
