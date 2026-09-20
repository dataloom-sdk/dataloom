package io.dataloom.assets

import io.dataloom.api.asset.AssetManifest
import kotlin.jvm.JvmInline

/**
 * Identifier of one transfer session. Chosen by the caller (not generated
 * here) so that retrying "start this transfer" after a crash addresses the
 * same session, and reused as the provider-side upload session id so the
 * provider can recognise a resumed upload.
 */
@JvmInline
public value class AssetTransferSessionId(public val value: String) {
    init {
        require(value.isNotBlank()) { "AssetTransferSessionId must not be blank." }
        require(value.length <= MAX_LENGTH) { "AssetTransferSessionId must be at most $MAX_LENGTH characters." }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 256
    }
}

/** Which way a transfer moves bytes. */
public enum class AssetTransferDirection {
    /** Local source to provider. */
    UPLOAD,

    /** Provider to local sink. */
    DOWNLOAD,
}

/**
 * Lifecycle phase of an [AssetTransferSession]. See [AssetTransferSession.reduce]
 * for the exact transition table.
 *
 * ```
 * CREATED --Start--> TRANSFERRING --BeginVerification--> VERIFYING --VerificationSucceeded--> COMPLETED
 *    \                    \                                   \
 *     +--Fail/Cancel-------+--------Fail/Cancel----------------+--> FAILED | CANCELLED
 * ```
 */
public enum class AssetTransferPhase(public val isTerminal: Boolean) {
    /** Session recorded; nothing transferred yet. */
    CREATED(false),

    /** Chunks are being committed (in any order). */
    TRANSFERRING(false),

    /** Every chunk is committed; whole-object integrity is being verified. */
    VERIFYING(false),

    /** Verified and complete. Terminal. */
    COMPLETED(true),

    /** Failed terminally; [AssetTransferSession.failure] says why. Terminal. */
    FAILED(true),

    /** Cancelled by the caller. Terminal. */
    CANCELLED(true),
}

/** An input to [AssetTransferSession.reduce]. */
public sealed interface AssetTransferEvent {

    /** Begin transferring: CREATED to TRANSFERRING. */
    public data object Start : AssetTransferEvent

    /**
     * Replace the committed-chunk set with the counterparty's authoritative
     * view (the provider's committed chunks on upload resume). Unlike
     * [ChunkCommitted] this can also *remove* chunks the provider has lost.
     */
    public class ReconcileCommitted(indices: Set<Int>) : AssetTransferEvent {
        public val indices: Set<Int> = indices.toSet()

        override fun equals(other: Any?): Boolean = other is ReconcileCommitted && indices == other.indices

        override fun hashCode(): Int = indices.hashCode()

        override fun toString(): String = "ReconcileCommitted(indices=$indices)"
    }

    /** One chunk is durably committed. Idempotent; chunks may commit in any order. */
    public data class ChunkCommitted(public val index: Int) : AssetTransferEvent

    /** All chunks committed: TRANSFERRING to VERIFYING. */
    public data object BeginVerification : AssetTransferEvent

    /** Whole-object verification passed: VERIFYING to COMPLETED. */
    public data object VerificationSucceeded : AssetTransferEvent

    /** Terminal failure of a non-terminal session (including a failed verification). */
    public data class Fail(public val kind: AssetErrorKind) : AssetTransferEvent

    /** Cancel a non-terminal session. */
    public data object Cancel : AssetTransferEvent
}

/** Why [AssetTransferSession.reduce] refused an event. */
public enum class AssetTransferRejection {
    /** The session is terminal and the event would change or contradict its outcome. */
    SESSION_TERMINAL,

    /** The event is not valid in the session's current (non-terminal) phase. */
    INVALID_PHASE,

    /** A chunk index is outside the manifest's chunk layout. */
    CHUNK_OUT_OF_RANGE,

    /** Verification was requested before every chunk was committed. */
    CHUNKS_MISSING,
}

/** Result of [AssetTransferSession.reduce]. */
public sealed interface AssetTransferTransition {

    /** The resulting (or unchanged) session. */
    public val session: AssetTransferSession

    /** The event changed the session; [session] has a new revision. */
    public data class Applied(override val session: AssetTransferSession) : AssetTransferTransition

    /** The event was an idempotent duplicate of something already reflected; nothing changed. */
    public data class Unchanged(override val session: AssetTransferSession) : AssetTransferTransition

    /** The event is invalid here; nothing changed. */
    public data class Rejected(
        override val session: AssetTransferSession,
        public val reason: AssetTransferRejection,
    ) : AssetTransferTransition
}

/**
 * Immutable state of one asset transfer: which asset, which direction, which
 * chunks are committed, and which lifecycle [phase] it is in. It is a pure
 * value; all behaviour is the [reduce] function, which makes the state
 * machine exhaustively testable without I/O, and lets a later slice persist
 * it through `DurableStateStore` (with [revision] as the compare-and-set
 * version) without changing its semantics.
 *
 * ## Invariants (checked on construction, so a persisted session cannot be
 * rehydrated into an impossible state)
 *
 * - every committed index lies inside the manifest's chunk layout;
 * - `phase == VERIFYING` or `COMPLETED` implies every chunk is committed;
 * - `failure != null` exactly when `phase == FAILED`;
 * - [revision] is non-negative.
 *
 * @param manifest the asset being transferred; its chunk layout is the
 *   session's geometry.
 * @param committedChunks indices of chunks durably committed. Defensively
 *   copied.
 * @param failure terminal failure kind when [phase] is FAILED, else `null`.
 * @param revision incremented by every applied event; the optimistic
 *   concurrency token for [AssetTransferSessionStore].
 */
public class AssetTransferSession(
    public val sessionId: AssetTransferSessionId,
    public val direction: AssetTransferDirection,
    public val manifest: AssetManifest,
    public val phase: AssetTransferPhase = AssetTransferPhase.CREATED,
    committedChunks: Set<Int> = emptySet(),
    public val failure: AssetErrorKind? = null,
    public val revision: Long = 0,
) {
    /** Committed chunk indices, ascending. */
    public val committedChunks: Set<Int> = committedChunks.sorted().toCollection(LinkedHashSet())

    /** Chunk indices not yet committed, ascending. */
    public val missingChunks: List<Int>
        get() = (0 until manifest.chunkLayout.chunkCount).filter { it !in committedChunks }

    /** `true` once every chunk is committed. */
    public val isFullyCommitted: Boolean
        get() = this.committedChunks.size == manifest.chunkLayout.chunkCount

    init {
        val chunkCount = manifest.chunkLayout.chunkCount
        require(this.committedChunks.all { it in 0 until chunkCount }) {
            "AssetTransferSession.committedChunks must lie within 0 until $chunkCount."
        }
        require(revision >= 0) { "AssetTransferSession.revision must not be negative, but was $revision." }
        require((failure != null) == (phase == AssetTransferPhase.FAILED)) {
            "AssetTransferSession.failure must be set exactly when phase is FAILED."
        }
        require(
            (phase != AssetTransferPhase.VERIFYING && phase != AssetTransferPhase.COMPLETED) || isFullyCommitted,
        ) { "A $phase AssetTransferSession must have every chunk committed." }
    }

    /**
     * Applies [event] and reports the outcome. Pure and total: every
     * (phase, event) pair is defined.
     *
     * | Event | CREATED | TRANSFERRING | VERIFYING | COMPLETED | FAILED | CANCELLED |
     * |---|---|---|---|---|---|---|
     * | Start | Applied to TRANSFERRING | Unchanged | Unchanged | Unchanged | Rejected TERMINAL | Rejected TERMINAL |
     * | ReconcileCommitted | Rejected INVALID_PHASE | Applied / Unchanged if equal | Rejected INVALID_PHASE | Rejected TERMINAL | Rejected TERMINAL | Rejected TERMINAL |
     * | ChunkCommitted | Rejected INVALID_PHASE | Applied / Unchanged if known | Unchanged | Unchanged | Rejected TERMINAL | Rejected TERMINAL |
     * | BeginVerification | Rejected INVALID_PHASE | Applied to VERIFYING, or Rejected CHUNKS_MISSING | Unchanged | Unchanged | Rejected TERMINAL | Rejected TERMINAL |
     * | VerificationSucceeded | Rejected INVALID_PHASE | Rejected INVALID_PHASE | Applied to COMPLETED | Unchanged | Rejected TERMINAL | Rejected TERMINAL |
     * | Fail(kind) | Applied to FAILED | Applied to FAILED | Applied to FAILED | Rejected TERMINAL | Unchanged if same kind, else Rejected TERMINAL | Rejected TERMINAL |
     * | Cancel | Applied to CANCELLED | Applied to CANCELLED | Applied to CANCELLED | Rejected TERMINAL | Rejected TERMINAL | Unchanged |
     *
     * A `ChunkCommitted` index outside the layout is Rejected CHUNK_OUT_OF_RANGE
     * in every phase; `ReconcileCommitted` range-checks only in TRANSFERRING, the
     * only phase in which it is otherwise valid. "Unchanged" means the event is
     * an idempotent duplicate of something already reflected, so redelivery,
     * retry and restart never corrupt a session. A completed session can never be
     * failed or cancelled ("no false completion" cuts both ways), and a
     * failed or cancelled session can never later complete.
     */
    public fun reduce(event: AssetTransferEvent): AssetTransferTransition {
        val chunkCount = manifest.chunkLayout.chunkCount
        return when (event) {
            AssetTransferEvent.Start -> when (phase) {
                AssetTransferPhase.CREATED -> applied(phase = AssetTransferPhase.TRANSFERRING)
                AssetTransferPhase.TRANSFERRING,
                AssetTransferPhase.VERIFYING,
                AssetTransferPhase.COMPLETED,
                -> unchanged()
                AssetTransferPhase.FAILED, AssetTransferPhase.CANCELLED -> rejected(AssetTransferRejection.SESSION_TERMINAL)
            }

            is AssetTransferEvent.ReconcileCommitted -> when (phase) {
                AssetTransferPhase.CREATED, AssetTransferPhase.VERIFYING -> rejected(AssetTransferRejection.INVALID_PHASE)
                AssetTransferPhase.TRANSFERRING -> when {
                    event.indices.any { it !in 0 until chunkCount } ->
                        rejected(AssetTransferRejection.CHUNK_OUT_OF_RANGE)
                    event.indices == committedChunks -> unchanged()
                    else -> applied(committedChunks = event.indices)
                }
                AssetTransferPhase.COMPLETED,
                AssetTransferPhase.FAILED,
                AssetTransferPhase.CANCELLED,
                -> rejected(AssetTransferRejection.SESSION_TERMINAL)
            }

            is AssetTransferEvent.ChunkCommitted -> when {
                event.index !in 0 until chunkCount -> rejected(AssetTransferRejection.CHUNK_OUT_OF_RANGE)
                else -> when (phase) {
                    AssetTransferPhase.CREATED -> rejected(AssetTransferRejection.INVALID_PHASE)
                    AssetTransferPhase.TRANSFERRING ->
                        if (event.index in committedChunks) {
                            unchanged()
                        } else {
                            applied(committedChunks = committedChunks + event.index)
                        }
                    // VERIFYING and COMPLETED imply every chunk is already committed.
                    AssetTransferPhase.VERIFYING, AssetTransferPhase.COMPLETED -> unchanged()
                    AssetTransferPhase.FAILED, AssetTransferPhase.CANCELLED ->
                        rejected(AssetTransferRejection.SESSION_TERMINAL)
                }
            }

            AssetTransferEvent.BeginVerification -> when (phase) {
                AssetTransferPhase.CREATED -> rejected(AssetTransferRejection.INVALID_PHASE)
                AssetTransferPhase.TRANSFERRING ->
                    if (isFullyCommitted) {
                        applied(phase = AssetTransferPhase.VERIFYING)
                    } else {
                        rejected(AssetTransferRejection.CHUNKS_MISSING)
                    }
                AssetTransferPhase.VERIFYING, AssetTransferPhase.COMPLETED -> unchanged()
                AssetTransferPhase.FAILED, AssetTransferPhase.CANCELLED ->
                    rejected(AssetTransferRejection.SESSION_TERMINAL)
            }

            AssetTransferEvent.VerificationSucceeded -> when (phase) {
                AssetTransferPhase.CREATED, AssetTransferPhase.TRANSFERRING ->
                    rejected(AssetTransferRejection.INVALID_PHASE)
                AssetTransferPhase.VERIFYING -> applied(phase = AssetTransferPhase.COMPLETED)
                AssetTransferPhase.COMPLETED -> unchanged()
                AssetTransferPhase.FAILED, AssetTransferPhase.CANCELLED ->
                    rejected(AssetTransferRejection.SESSION_TERMINAL)
            }

            is AssetTransferEvent.Fail -> when (phase) {
                AssetTransferPhase.CREATED, AssetTransferPhase.TRANSFERRING, AssetTransferPhase.VERIFYING ->
                    applied(phase = AssetTransferPhase.FAILED, failure = event.kind)
                AssetTransferPhase.FAILED ->
                    if (failure == event.kind) unchanged() else rejected(AssetTransferRejection.SESSION_TERMINAL)
                AssetTransferPhase.COMPLETED, AssetTransferPhase.CANCELLED ->
                    rejected(AssetTransferRejection.SESSION_TERMINAL)
            }

            AssetTransferEvent.Cancel -> when (phase) {
                AssetTransferPhase.CREATED, AssetTransferPhase.TRANSFERRING, AssetTransferPhase.VERIFYING ->
                    applied(phase = AssetTransferPhase.CANCELLED)
                AssetTransferPhase.CANCELLED -> unchanged()
                AssetTransferPhase.COMPLETED, AssetTransferPhase.FAILED ->
                    rejected(AssetTransferRejection.SESSION_TERMINAL)
            }
        }
    }

    private fun applied(
        phase: AssetTransferPhase = this.phase,
        committedChunks: Set<Int> = this.committedChunks,
        failure: AssetErrorKind? = this.failure,
    ): AssetTransferTransition = AssetTransferTransition.Applied(
        AssetTransferSession(sessionId, direction, manifest, phase, committedChunks, failure, revision + 1),
    )

    private fun unchanged(): AssetTransferTransition = AssetTransferTransition.Unchanged(this)

    private fun rejected(reason: AssetTransferRejection): AssetTransferTransition =
        AssetTransferTransition.Rejected(this, reason)

    override fun equals(other: Any?): Boolean =
        other is AssetTransferSession &&
            sessionId == other.sessionId &&
            direction == other.direction &&
            manifest == other.manifest &&
            phase == other.phase &&
            committedChunks == other.committedChunks &&
            failure == other.failure &&
            revision == other.revision

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + manifest.hashCode()
        result = 31 * result + phase.hashCode()
        result = 31 * result + committedChunks.hashCode()
        result = 31 * result + (failure?.hashCode() ?: 0)
        result = 31 * result + revision.hashCode()
        return result
    }

    override fun toString(): String =
        "AssetTransferSession(sessionId=$sessionId, direction=$direction, assetId=${manifest.assetId}, " +
            "phase=$phase, committed=${committedChunks.size}/${manifest.chunkLayout.chunkCount}, " +
            "failure=$failure, revision=$revision)"
}
