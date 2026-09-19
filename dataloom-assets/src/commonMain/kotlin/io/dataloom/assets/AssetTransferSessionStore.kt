package io.dataloom.assets

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistence seam for [AssetTransferSession]s.
 *
 * This slice ships only [InMemoryAssetTransferSessionStore]. A later slice
 * adds a durable implementation on the existing `DurableStateStore` domain
 * adoption pattern; because the seam is a compare-and-set on
 * [AssetTransferSession.revision], that adoption needs no change to the
 * engine or the session state machine.
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
