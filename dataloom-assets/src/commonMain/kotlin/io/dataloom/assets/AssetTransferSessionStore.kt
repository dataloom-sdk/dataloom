package io.dataloom.assets

import io.dataloom.api.error.DataLoomError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistence seam for [AssetTransferSession]s.
 *
 * [InMemoryAssetTransferSessionStore] is the volatile implementation;
 * [DurableAssetTransferSessionStore] persists through the generic
 * `DurableStateStore` so an in-flight transfer survives a process restart.
 * Both are compare-and-set on [AssetTransferSession.revision], so the engine
 * and the session state machine are identical over either.
 *
 * A store that can fail for reasons other than a lost race (I/O, corruption)
 * reports it by throwing [AssetTransferSessionStoreException]; the engine turns
 * that into [AssetTransferOutcome.SessionStoreFailure] instead of guessing at
 * session state.
 */
public interface AssetTransferSessionStore {

    /** Returns the stored session, or `null` if none exists for [sessionId]. */
    public suspend fun load(sessionId: AssetTransferSessionId): AssetTransferSession?

    /**
     * Stores [updated] if and only if the currently stored revision equals
     * [expectedRevision] (`null` meaning "no session is stored yet").
     *
     * @return `true` if stored; `false` if another writer got there first, in
     *   which case the caller reloads and re-applies its event.
     */
    public suspend fun save(updated: AssetTransferSession, expectedRevision: Long?): Boolean
}

/**
 * Thrown by an [AssetTransferSessionStore] whose backing storage failed, was
 * contended past its retry bound, or holds an unusable record. [error] is the
 * sanitised canonical error (an [AssetTransferError] for failures this module
 * classifies, or the underlying store's own [DataLoomError]).
 */
public class AssetTransferSessionStoreException(
    public val error: DataLoomError,
) : RuntimeException("Asset transfer session store failed: ${error.code}")

/** Volatile [AssetTransferSessionStore] for tests and the in-memory reference flow. */
public class InMemoryAssetTransferSessionStore : AssetTransferSessionStore {
    private val mutex = Mutex()
    private val sessions = HashMap<AssetTransferSessionId, AssetTransferSession>()

    override suspend fun load(sessionId: AssetTransferSessionId): AssetTransferSession? =
        mutex.withLock { sessions[sessionId] }

    override suspend fun save(updated: AssetTransferSession, expectedRevision: Long?): Boolean =
        mutex.withLock {
            val current = sessions[updated.sessionId]
            if (current?.revision != expectedRevision) return@withLock false
            sessions[updated.sessionId] = updated
            true
        }
}

/**
 * Applies [event] to the stored session with optimistic-concurrency retry, and
 * returns the transition whose `session` is the latest stored state.
 *
 * Only [AssetTransferTransition.Applied] results are persisted. A
 * [AssetTransferTransition.Rejected] or [AssetTransferTransition.Unchanged]
 * result carries the session as it currently is, so callers observing a
 * concurrent cancel see the terminal state.
 *
 * @throws IllegalStateException if no session is stored for [sessionId].
 */
public suspend fun AssetTransferSessionStore.applyEvent(
    sessionId: AssetTransferSessionId,
    event: AssetTransferEvent,
): AssetTransferTransition {
    while (true) {
        val current = checkNotNull(load(sessionId)) { "No asset transfer session stored for $sessionId." }
        val transition = current.reduce(event)
        if (transition !is AssetTransferTransition.Applied) return transition
        if (save(transition.session, current.revision)) return transition
    }
}
