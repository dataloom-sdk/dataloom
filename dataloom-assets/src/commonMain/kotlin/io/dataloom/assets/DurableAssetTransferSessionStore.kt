package io.dataloom.assets

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore

/** Outcome of one [DurableAssetTransferSessionStore.trySave] call. */
public sealed interface DurableAssetTransferSessionSaveOutcome {

    /** [session] is now the persisted state for its session id. */
    public data class Saved(public val session: AssetTransferSession) : DurableAssetTransferSessionSaveOutcome

    /**
     * The persisted revision did not match the expected one (another worker
     * advanced the session first, or the session already exists when it was
     * expected to be absent, or is absent when a revision was expected).
     * Nothing was persisted. [current] is the session as it is now, or `null`
     * if none is stored; the caller reloads, re-applies its event and retries.
     */
    public data class StaleRevision(public val current: AssetTransferSession?) : DurableAssetTransferSessionSaveOutcome

    /** The underlying [DurableStateStore] failed, or holds an unusable record. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : DurableAssetTransferSessionSaveOutcome

    /**
     * [DurableAssetTransferSessionStore.maximumStateUpdateAttempts] consecutive
     * compare-and-set attempts lost the race to concurrent writers. Nothing was
     * persisted; the caller may retry.
     */
    public data object ContentionLimitReached : DurableAssetTransferSessionSaveOutcome
}

/** Outcome of one [DurableAssetTransferSessionStore.tryLoad] call. */
public sealed interface DurableAssetTransferSessionLoadOutcome {

    /** No session is persisted for the requested id. */
    public data object Missing : DurableAssetTransferSessionLoadOutcome

    /** The persisted session. */
    public data class Found(public val session: AssetTransferSession) : DurableAssetTransferSessionLoadOutcome

    /** The underlying [DurableStateStore] failed, or the record is unusable. */
    public data class PersistenceFailure(public val error: DataLoomError) : DurableAssetTransferSessionLoadOutcome
}

/**
 * Durable [AssetTransferSessionStore] backed by a [DurableStateStore]: the
 * adoption of the generic durable-state pattern for transfer sessions, so an
 * interrupted upload or download resumes after a process restart instead of
 * starting over. Same shape as
 * [io.dataloom.api.asset.DurableAssetManifestHistory] and
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog]: per-scope
 * compare-and-set with a bounded reload-and-retry loop, a versioned codec
 * ([AssetTransferSessionCodec]), and typed outcomes.
 *
 * ## Scope and state
 *
 * `TScope` is [AssetTransferSessionId] (reused, not wrapped); `TState` is
 * [AssetTransferSession] itself, which carries the manifest with its per-chunk
 * and whole-object digests, the committed chunk indices, the phase and the
 * failure kind. It never holds payload bytes.
 *
 * ## Two version counters
 *
 * The [DurableStateStore] record `version` is the storage-level CAS counter;
 * [AssetTransferSession.revision] is the domain-level counter the engine and
 * state machine use. This class maps between them: a save is accepted when the
 * persisted session's `revision` equals the caller's expected revision, and it
 * is then written with a CAS on the record `version` it just read. If another
 * writer intervenes between the read and the write, the CAS reports a conflict
 * and the whole check is repeated against the fresh state, up to
 * [maximumStateUpdateAttempts] times.
 *
 * ## Concurrent workers
 *
 * Two workers (or a worker and a restarted copy of itself) may commit the same
 * chunk. Exactly one write of a given revision wins; the other's save is
 * [DurableAssetTransferSessionSaveOutcome.StaleRevision], so the engine's
 * `applyEvent` loop reloads, sees the chunk already committed and gets an
 * idempotent `Unchanged` transition: the two converge on one session.
 *
 * ## Failure reporting
 *
 * [AssetTransferSessionStore.save] returns `false` only for a lost race. A
 * storage failure, retry exhaustion, a schema version this instance does not
 * understand, a record whose session id differs from its scope, or a record
 * the codec rejects is thrown as [AssetTransferSessionStoreException], which
 * [AssetTransferEngine] reports as [AssetTransferOutcome.SessionStoreFailure].
 * The typed [trySave]/[tryLoad] are available to callers that want outcomes
 * instead of exceptions.
 *
 * @param store durable persistence for sessions. A platform store (for example
 *   `RoomDurableStateStore`) needs [AssetTransferSessionCodec] and [KeyEncoder].
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes and requires when reading. A record persisted under a
 *   different schema version is reported as corrupt rather than guessed at.
 * @param maximumStateUpdateAttempts bounded compare-and-set attempts per
 *   [trySave]. Must be at least `1`.
 */
public class DurableAssetTransferSessionStore(
    private val store: DurableStateStore<AssetTransferSessionId, AssetTransferSession>,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) : AssetTransferSessionStore {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
    }

    /** Typed variant of [load]. */
    public suspend fun tryLoad(sessionId: AssetTransferSessionId): DurableAssetTransferSessionLoadOutcome =
        when (val loaded = store.load(sessionId)) {
            is ProviderOperationResult.Failure -> DurableAssetTransferSessionLoadOutcome.PersistenceFailure(loaded.error)
            is ProviderOperationResult.Success -> when (val result = loaded.value) {
                is DurableStateLoadResult.Missing -> DurableAssetTransferSessionLoadOutcome.Missing
                is DurableStateLoadResult.Found -> checkRecord(sessionId, result.record.schemaVersion, result.record.state)
                    ?.let { DurableAssetTransferSessionLoadOutcome.PersistenceFailure(it) }
                    ?: DurableAssetTransferSessionLoadOutcome.Found(result.record.state)
            }
        }

    /**
     * Typed variant of [save]: persists [updated] if the stored revision equals
     * [expectedRevision] (`null` meaning "no session stored yet").
     */
    public suspend fun trySave(
        updated: AssetTransferSession,
        expectedRevision: Long?,
    ): DurableAssetTransferSessionSaveOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(updated.sessionId)) {
                is ProviderOperationResult.Failure ->
                    return DurableAssetTransferSessionSaveOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val expectedVersion: Long?
            when (loaded) {
                is DurableStateLoadResult.Missing -> {
                    if (expectedRevision != null) return DurableAssetTransferSessionSaveOutcome.StaleRevision(null)
                    expectedVersion = null
                }
                is DurableStateLoadResult.Found -> {
                    checkRecord(updated.sessionId, loaded.record.schemaVersion, loaded.record.state)
                        ?.let { return DurableAssetTransferSessionSaveOutcome.PersistenceFailure(it) }
                    if (loaded.record.state.revision != expectedRevision) {
                        return DurableAssetTransferSessionSaveOutcome.StaleRevision(loaded.record.state)
                    }
                    expectedVersion = loaded.record.version
                }
            }
            when (
                val result = store.compareAndSet(
                    DurableStateCompareAndSetRequest(
                        scope = updated.sessionId,
                        expectedVersion = expectedVersion,
                        nextState = updated,
                        nextSchemaVersion = schemaVersion,
                    ),
                )
            ) {
                is ProviderOperationResult.Failure ->
                    return DurableAssetTransferSessionSaveOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> when (result.value) {
                    is DurableStateCompareAndSetResult.Conflict -> Unit // lost the race; reload and re-check
                    is DurableStateCompareAndSetResult.Updated ->
                        return DurableAssetTransferSessionSaveOutcome.Saved(updated)
                }
            }
        }
        return DurableAssetTransferSessionSaveOutcome.ContentionLimitReached
    }

    override suspend fun load(sessionId: AssetTransferSessionId): AssetTransferSession? =
        when (val outcome = tryLoad(sessionId)) {
            is DurableAssetTransferSessionLoadOutcome.Missing -> null
            is DurableAssetTransferSessionLoadOutcome.Found -> outcome.session
            is DurableAssetTransferSessionLoadOutcome.PersistenceFailure ->
                throw AssetTransferSessionStoreException(outcome.error)
        }

    override suspend fun save(updated: AssetTransferSession, expectedRevision: Long?): Boolean =
        when (val outcome = trySave(updated, expectedRevision)) {
            is DurableAssetTransferSessionSaveOutcome.Saved -> true
            is DurableAssetTransferSessionSaveOutcome.StaleRevision -> false
            is DurableAssetTransferSessionSaveOutcome.PersistenceFailure ->
                throw AssetTransferSessionStoreException(outcome.error)
            is DurableAssetTransferSessionSaveOutcome.ContentionLimitReached ->
                throw AssetTransferSessionStoreException(
                    AssetTransferError(AssetErrorKind.SESSION_STORE_FAILURE, "Session store was contended past its retry bound."),
                )
        }

    private fun checkRecord(scope: AssetTransferSessionId, recordSchemaVersion: Int, state: AssetTransferSession): DataLoomError? =
        when {
            recordSchemaVersion != schemaVersion ->
                AssetTransferError(AssetErrorKind.SESSION_STATE_CORRUPT, "Persisted session has an unsupported schema version.")
            state.sessionId != scope ->
                AssetTransferError(AssetErrorKind.SESSION_STATE_CORRUPT, "Persisted session does not belong to its scope.")
            else -> null
        }

    public companion object {
        /**
         * Reference [DurableStateScopeKeyEncoder]: the session id is already
         * validated non-blank and bounded, and is the whole scope identity, so
         * no escaping or composition is needed.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<AssetTransferSessionId> =
            DurableStateScopeKeyEncoder { it.value }

        private const val DEFAULT_SCHEMA_VERSION: Int = 1
        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
