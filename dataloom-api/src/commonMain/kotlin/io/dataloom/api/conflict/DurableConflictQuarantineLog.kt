package io.dataloom.api.conflict

import io.dataloom.api.change.EntityReference
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant

/**
 * Identifies the entity whose repeated conflicts a [DurableConflictQuarantineLog]
 * counts: entity type plus entity ID. The entity *version* is deliberately not
 * part of the scope -- a loop is the same entity conflicting again, whichever
 * version the latest attempt saw.
 */
public data class ConflictQuarantineScope(
    public val entityType: EntityType,
    public val entityId: EntityId,
) {
    public companion object {
        /** The scope of the entity a conflict is about. */
        public fun of(entity: EntityReference): ConflictQuarantineScope =
            ConflictQuarantineScope(entity.type, entity.id)

        /**
         * Reference [DurableStateScopeKeyEncoder] for [ConflictQuarantineScope].
         * Length-prefixes each field (the scheme
         * [io.dataloom.api.policy.PolicyDecisionScope] uses) so no entity type
         * or ID content can shift a field boundary and collide with another
         * scope's key.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<ConflictQuarantineScope> =
            DurableStateScopeKeyEncoder { scope ->
                buildString {
                    val type = scope.entityType.value
                    val id = scope.entityId.value
                    append(type.length)
                    append(':')
                    append(type)
                    append('|')
                    append(id.length)
                    append(':')
                    append(id)
                }
            }
    }
}

/**
 * When repeated conflicts on one entity are treated as a non-converging loop.
 *
 * The [occurrenceThreshold]-th counted occurrence quarantines the entity: the
 * first `occurrenceThreshold - 1` occurrences are resolved as normal, and the
 * occurrence that reaches the threshold is not resolved. Counting starts at an
 * entity's first occurrence (or its first after a release, or after a window
 * expiry) and is not reset by anything else, including a successfully
 * resolved conflict -- see [DurableConflictQuarantineLog] for why.
 *
 * @param occurrenceThreshold occurrences that quarantine the entity. At least
 *   `2`; a threshold of `1` would quarantine every conflict.
 * @param windowMillis optional bound on the counting window, in milliseconds:
 *   an occurrence more than this long after the first occurrence of the
 *   current window starts a new window at count `1`. `null` (the default)
 *   means occurrences accumulate until the entity is quarantined or released.
 */
public data class ConflictQuarantinePolicy(
    public val occurrenceThreshold: Int = DEFAULT_OCCURRENCE_THRESHOLD,
    public val windowMillis: Long? = null,
) {
    init {
        require(occurrenceThreshold >= 2) {
            "ConflictQuarantinePolicy occurrenceThreshold must be at least 2, but was $occurrenceThreshold."
        }
        require(windowMillis == null || windowMillis > 0L) {
            "ConflictQuarantinePolicy windowMillis must be null or positive, but was $windowMillis."
        }
    }

    public companion object {
        /** Default number of occurrences that quarantines an entity. */
        public const val DEFAULT_OCCURRENCE_THRESHOLD: Int = 5
    }
}

/** Whether an entity's conflicts are still being counted or are quarantined. */
public enum class ConflictQuarantineStatus {
    /** Conflicts are counted and resolved as normal. */
    COUNTING,

    /** The threshold was reached; conflicts are not re-resolved until released. */
    QUARANTINED,
}

/**
 * Durable evidence of the authorized command that released an entity from
 * quarantine. Written atomically with the release itself, so there is no
 * window in which an entity is released without this audit evidence.
 */
public data class ConflictQuarantineRelease(
    public val commandId: ConflictAdministrationCommandId,
    public val principalId: ConflictAdministrationPrincipalId,
    public val authorizationId: ConflictAdministrationAuthorizationId,
    public val reason: ConflictAdministrationReason,
    public val releasedAt: DataLoomInstant,
)

/**
 * Durable `TState` persisted per [ConflictQuarantineScope]. Payload-free: only
 * counts, timestamps, and identifiers.
 *
 * @param occurrenceCount occurrences counted in the current window. `0` only
 *   directly after a release.
 * @param firstSeenAt when the current window's first occurrence was observed.
 * @param lastSeenAt when the most recent occurrence was observed.
 * @param lastConflictId the [ConflictId] of the most recent occurrence.
 * @param lastResolverId the resolver ID selected for the most recent
 *   occurrence, or `null` when none was selected.
 * @param quarantinedAt when the threshold was reached; non-`null` exactly when
 *   [status] is [ConflictQuarantineStatus.QUARANTINED].
 * @param releaseCount how many times this entity has been released.
 * @param lastRelease evidence of the most recent release; non-`null` exactly
 *   when [releaseCount] is positive.
 */
public data class ConflictQuarantineRecord(
    public val status: ConflictQuarantineStatus,
    public val occurrenceCount: Int,
    public val firstSeenAt: DataLoomInstant,
    public val lastSeenAt: DataLoomInstant,
    public val lastConflictId: ConflictId,
    public val lastResolverId: ConflictResolverId?,
    public val quarantinedAt: DataLoomInstant?,
    public val releaseCount: Int = 0,
    public val lastRelease: ConflictQuarantineRelease? = null,
) {
    init {
        require(occurrenceCount >= 0) { "ConflictQuarantineRecord occurrenceCount must not be negative." }
        require((status == ConflictQuarantineStatus.QUARANTINED) == (quarantinedAt != null)) {
            "ConflictQuarantineRecord quarantinedAt must be present exactly when status is QUARANTINED."
        }
        require(status != ConflictQuarantineStatus.QUARANTINED || occurrenceCount >= 1) {
            "ConflictQuarantineRecord cannot be QUARANTINED with no occurrences."
        }
        require(releaseCount >= 0) { "ConflictQuarantineRecord releaseCount must not be negative." }
        require((releaseCount > 0) == (lastRelease != null)) {
            "ConflictQuarantineRecord lastRelease must be present exactly when releaseCount is positive."
        }
    }

    /** `true` while conflicts on this entity are not being re-resolved. */
    public val isQuarantined: Boolean
        get() = status == ConflictQuarantineStatus.QUARANTINED
}

/** Outcome of one [DurableConflictQuarantineLog.recordOccurrence] call. */
public sealed interface ConflictQuarantineObservation {

    /** The occurrence was counted and the threshold is not reached: resolve as normal. */
    public data class Counted(public val record: ConflictQuarantineRecord) : ConflictQuarantineObservation

    /**
     * The entity is quarantined: do not resolve. [newlyQuarantined] is `true`
     * for the single occurrence that reached the threshold and `false` when
     * the entity was already quarantined (in which case nothing was written).
     */
    public data class Quarantined(
        public val record: ConflictQuarantineRecord,
        public val newlyQuarantined: Boolean,
    ) : ConflictQuarantineObservation

    /**
     * The occurrence could not be counted, so whether the entity is
     * quarantined is unknown. Callers that need the safety guarantee must fail
     * closed.
     */
    public sealed interface Unavailable : ConflictQuarantineObservation

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : Unavailable

    /**
     * Every bounded compare-and-set attempt lost to a concurrent writer.
     * Nothing was persisted by this call; the caller may retry.
     */
    public data object ContentionLimitReached : Unavailable
}

/** Outcome of one [DurableConflictQuarantineLog.release] call. */
public sealed interface ConflictQuarantineReleaseOutcome {

    /** The entity was quarantined and is now released; counting restarts from zero. */
    public data class Released(public val record: ConflictQuarantineRecord) : ConflictQuarantineReleaseOutcome

    /**
     * The same command already released this entity: an idempotent replay.
     * [record] is the unchanged current record.
     */
    public data class AlreadyReleased(public val record: ConflictQuarantineRecord) : ConflictQuarantineReleaseOutcome

    /** The entity is not currently quarantined (or has no record). Nothing was persisted. */
    public data class NotQuarantined(public val record: ConflictQuarantineRecord?) : ConflictQuarantineReleaseOutcome

    /** The underlying [DurableStateStore] failed. Nothing was persisted. */
    public data class PersistenceFailure(public val error: DataLoomError) : ConflictQuarantineReleaseOutcome

    /** Every bounded compare-and-set attempt lost to a concurrent writer. */
    public data object ContentionLimitReached : ConflictQuarantineReleaseOutcome
}

/**
 * Durable, per-entity loop/non-convergence counter and quarantine, backed by a
 * [DurableStateStore].
 *
 * ## Why this exists
 *
 * With the inbound pipeline's fail-closed handling (`Defer`, `Fail`,
 * unresolved outcomes block application and leave the checkpoint unadvanced),
 * the same remote batch is delivered again and re-conflicts on the same entity
 * indefinitely. Nothing bounded that. This log counts conflict occurrences per
 * [ConflictQuarantineScope] and, at the [ConflictQuarantinePolicy] threshold,
 * quarantines the entity so it is no longer re-resolved until an authorized
 * operator releases it.
 *
 * ## What counts
 *
 * Every detected conflict on the entity counts, whatever its outcome and
 * whatever its [ConflictId]: a replayed delivery of the same conflict is
 * exactly the loop being detected, so it must count. The cost of that
 * simplicity is that an entity which conflicts and is correctly resolved
 * several times over a long period also accumulates; hosts with such entities
 * should set [ConflictQuarantinePolicy.windowMillis] or a higher threshold.
 * A crash between recording an occurrence and finishing the batch replays the
 * occurrence, so counts are conservative (never lower than the true number).
 *
 * ## Concurrency
 *
 * Every write is a load-evaluate-compare-and-set loop bounded by
 * [maximumStateUpdateAttempts], so concurrent occurrences on one entity each
 * land exactly once: a loser reloads the winner's count and increments from it.
 * No occurrence is lost, and none is counted twice by a retry. Once an entity
 * is quarantined, further occurrences are read-only (no write, no churn).
 *
 * ## Release
 *
 * [release] is the storage half of the authorized release command (see
 * `ConflictAdministrationCoordinator.releaseQuarantine`); it never checks
 * authorization itself. The release evidence is written in the same
 * compare-and-set as the state change and is idempotent by command ID.
 *
 * @param store durable persistence for this log's [ConflictQuarantineRecord].
 * @param schemaVersion the [DurableStateRecord.schemaVersion] this instance
 *   writes.
 * @param maximumStateUpdateAttempts bounded compare-and-set attempts per call.
 *   Must be at least `1`.
 */
public class DurableConflictQuarantineLog(
    private val store: DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord>,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "maximumStateUpdateAttempts must be at least 1, but was $maximumStateUpdateAttempts."
        }
    }

    /** The current record for [scope], or `null` when no occurrence was ever counted. */
    public suspend fun current(scope: ConflictQuarantineScope): ProviderOperationResult<ConflictQuarantineRecord?> =
        when (val loaded = store.load(scope)) {
            is ProviderOperationResult.Failure -> loaded
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(
                (loaded.value as? DurableStateLoadResult.Found)?.record?.state,
            )
        }

    /**
     * Counts one conflict occurrence for [scope] observed at [observedAt] under
     * [policy] and reports whether the entity is now (or already was)
     * quarantined.
     */
    public suspend fun recordOccurrence(
        scope: ConflictQuarantineScope,
        conflictId: ConflictId,
        resolverId: ConflictResolverId?,
        observedAt: DataLoomInstant,
        policy: ConflictQuarantinePolicy,
    ): ConflictQuarantineObservation {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(scope)) {
                is ProviderOperationResult.Failure -> return ConflictQuarantineObservation.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val found: DurableStateRecord<ConflictQuarantineRecord>? = (loaded as? DurableStateLoadResult.Found)?.record
            val existing = found?.state
            if (existing != null && existing.isQuarantined) {
                return ConflictQuarantineObservation.Quarantined(existing, newlyQuarantined = false)
            }

            val next = nextOccurrence(existing, conflictId, resolverId, observedAt, policy)
            val update = store.compareAndSet(
                DurableStateCompareAndSetRequest(
                    scope = scope,
                    expectedVersion = found?.version,
                    nextState = next,
                    nextSchemaVersion = schemaVersion,
                ),
            )
            when (update) {
                is ProviderOperationResult.Failure -> return ConflictQuarantineObservation.PersistenceFailure(update.error)
                is ProviderOperationResult.Success -> when (update.value) {
                    is DurableStateCompareAndSetResult.Updated -> return if (next.isQuarantined) {
                        ConflictQuarantineObservation.Quarantined(next, newlyQuarantined = true)
                    } else {
                        ConflictQuarantineObservation.Counted(next)
                    }
                    // Lost the race: reload and increment from whatever won.
                    is DurableStateCompareAndSetResult.Conflict -> Unit
                }
            }
        }
        return ConflictQuarantineObservation.ContentionLimitReached
    }

    /**
     * Releases [scope] from quarantine, recording [release] as evidence, and
     * restarts its count at zero. Idempotent by
     * [ConflictQuarantineRelease.commandId].
     */
    public suspend fun release(
        scope: ConflictQuarantineScope,
        release: ConflictQuarantineRelease,
    ): ConflictQuarantineReleaseOutcome {
        repeat(maximumStateUpdateAttempts) {
            val loaded = when (val result = store.load(scope)) {
                is ProviderOperationResult.Failure -> return ConflictQuarantineReleaseOutcome.PersistenceFailure(result.error)
                is ProviderOperationResult.Success -> result.value
            }
            val found = (loaded as? DurableStateLoadResult.Found)?.record
            val existing = found?.state
            if (existing?.lastRelease?.commandId == release.commandId) {
                return ConflictQuarantineReleaseOutcome.AlreadyReleased(existing)
            }
            if (found == null || existing == null || !existing.isQuarantined) {
                return ConflictQuarantineReleaseOutcome.NotQuarantined(existing)
            }

            val next = existing.copy(
                status = ConflictQuarantineStatus.COUNTING,
                occurrenceCount = 0,
                quarantinedAt = null,
                releaseCount = existing.releaseCount + 1,
                lastRelease = release,
            )
            val update = store.compareAndSet(
                DurableStateCompareAndSetRequest(
                    scope = scope,
                    expectedVersion = found.version,
                    nextState = next,
                    nextSchemaVersion = schemaVersion,
                ),
            )
            when (update) {
                is ProviderOperationResult.Failure -> return ConflictQuarantineReleaseOutcome.PersistenceFailure(update.error)
                is ProviderOperationResult.Success -> when (update.value) {
                    is DurableStateCompareAndSetResult.Updated -> return ConflictQuarantineReleaseOutcome.Released(next)
                    is DurableStateCompareAndSetResult.Conflict -> Unit
                }
            }
        }
        return ConflictQuarantineReleaseOutcome.ContentionLimitReached
    }

    private fun nextOccurrence(
        existing: ConflictQuarantineRecord?,
        conflictId: ConflictId,
        resolverId: ConflictResolverId?,
        observedAt: DataLoomInstant,
        policy: ConflictQuarantinePolicy,
    ): ConflictQuarantineRecord {
        val startsNewWindow = existing == null ||
            existing.occurrenceCount == 0 ||
            policy.windowMillis?.let { window ->
                observedAt.epochMilliseconds - existing.firstSeenAt.epochMilliseconds > window
            } == true
        val count = if (startsNewWindow) 1 else checkNotNull(existing).occurrenceCount + 1
        val quarantined = count >= policy.occurrenceThreshold
        return ConflictQuarantineRecord(
            status = if (quarantined) ConflictQuarantineStatus.QUARANTINED else ConflictQuarantineStatus.COUNTING,
            occurrenceCount = count,
            firstSeenAt = if (startsNewWindow) observedAt else checkNotNull(existing).firstSeenAt,
            // Keeps lastSeenAt monotonic if the host clock steps backwards.
            lastSeenAt = if (existing != null && existing.lastSeenAt.epochMilliseconds > observedAt.epochMilliseconds) {
                existing.lastSeenAt
            } else {
                observedAt
            },
            lastConflictId = conflictId,
            lastResolverId = resolverId,
            quarantinedAt = if (quarantined) observedAt else null,
            releaseCount = existing?.releaseCount ?: 0,
            lastRelease = existing?.lastRelease,
        )
    }

    public companion object {
        private const val DEFAULT_SCHEMA_VERSION: Int = 1
        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
