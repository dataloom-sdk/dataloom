package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.identifier.ConflictResolverId

/**
 * The first deterministic built-in [ConflictResolver] this codebase ships,
 * closing a bounded first slice of DL-041's "deterministic built-in
 * strategies" requirement.
 *
 * ## Honest naming caveat: this is not evidence-based recency ordering
 *
 * The conventional "last write wins" strategy resolves a conflict in favor of
 * whichever change happened most recently in wall-clock time. This
 * implementation cannot do that today, because no reliable recency evidence
 * exists anywhere on the inputs it is allowed to inspect:
 *
 * - [io.dataloom.api.change.ChangeEvent] is explicitly documented as never
 *   generating or carrying "any identifiers or timestamps."
 * - [io.dataloom.api.change.EntityReference.version] is
 *   [io.dataloom.api.payload.EntityVersion], which [ConflictResolver] itself
 *   documents as opaque: "DataLoom does not assume numeric ordering,
 *   timestamps, or ETag semantics."
 * - [io.dataloom.api.model.SynchronizationRequest] (reachable via
 *   [ConflictResolutionRequest.synchronizationRequest]) carries no request or
 *   submission timestamp either.
 *
 * Inventing a new timestamp field on [io.dataloom.api.change.ChangeEvent] or
 * [io.dataloom.api.conflict.SynchronizationConflict] to make true recency
 * ordering possible is a real, separate, larger design question -- it would
 * change a widely shared contract used well beyond conflict resolution, and
 * is explicitly out of scope for this bounded first slice.
 *
 * Given that gap, this resolver applies the smallest honest fallback instead:
 * a **deterministic remote-wins tiebreak**. It always returns
 * [ConflictResolutionDecision.UseRemote] for every
 * [io.dataloom.api.conflict.SynchronizationConflict], regardless of
 * [io.dataloom.api.conflict.ConflictType] or the content of either side's
 * change. This is a named, tracked placeholder policy, not a claim of true
 * "most recently written data wins" semantics -- a caller who genuinely
 * needs evidence-based recency ordering must supply their own
 * [ConflictResolver] once such evidence exists in this codebase's contracts.
 *
 * ## Why remote, not local
 *
 * Remote-wins is chosen over local-wins as the placeholder default because it
 * matches this codebase's existing default synchronization posture: inbound
 * pull already applies remote changes locally by default
 * ([io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline]),
 * so a caller who wants the opposite placeholder behavior can trivially
 * compose their own resolver returning [ConflictResolutionDecision.UseLocal]
 * unconditionally instead of registering this one.
 *
 * ## Determinism and side effects
 *
 * [resolve] performs no I/O, no randomness, and no dependence on wall-clock
 * time -- it inspects nothing about [request] beyond routing to the fixed
 * decision, satisfying [ConflictResolver]'s synchronous/deterministic
 * contract trivially.
 *
 * @param id the [ConflictResolverId] this resolver registers under.
 *   Defaults to [DEFAULT_ID].
 */
public class LastWriteWinsConflictResolver(
    override val id: ConflictResolverId = DEFAULT_ID,
) : ConflictResolver {

    override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision =
        ConflictResolutionDecision.UseRemote()

    override fun toString(): String = "LastWriteWinsConflictResolver(id=${id.value})"

    public companion object {
        /** Reference [ConflictResolverId] for this built-in resolver. */
        public val DEFAULT_ID: ConflictResolverId = ConflictResolverId("dataloom.builtin.last-write-wins")
    }
}
