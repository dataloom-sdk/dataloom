package io.dataloom.runtime.facade

import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.state.DurableStateStore
import io.dataloom.runtime.conflict.ConflictOrchestrationBindings

/**
 * Application-owned configuration that turns on real conflict detection
 * during inbound pull.
 *
 * ## Why this exists
 *
 * [io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline]
 * has accepted an optional
 * [io.dataloom.runtime.execution.inbound.InboundPullConflictDetectionConfiguration]
 * since `#258`, and `StorageProvider.readLocalConflictCandidate` has existed
 * since the same change — but nothing in [DataLoomBuilder] ever constructed
 * one. Every pipeline [DataLoomBuilder] assembled ran with conflict
 * detection permanently disabled regardless of what an application
 * registered, because there was no way to reach it. This spec is that
 * missing connection.
 *
 * ## What the application must supply
 *
 * - [detectors]/[resolvers]: the same
 * [io.dataloom.runtime.conflict.ConflictDetector]/[io.dataloom.runtime.conflict.ConflictResolver]
 *   implementations [io.dataloom.runtime.conflict.SynchronizationConflictOrchestrator]
 *   already accepted before this spec existed — this does not introduce a
 *   new detector/resolver contract.
 * - [bindings]: which detector (required) and resolver (optional) to use
 *   for every detection call. One binding applies to the whole pipeline
 *   instance — there is no per-entity-type binding, matching
 *   [io.dataloom.runtime.execution.inbound.InboundPullConflictDetectionConfiguration]'s
 *   own documented scope.
 * - [unresolvedConflictStore]: a real [DurableStateStore] for
 *   [DurableUnresolvedConflictLog] to persist conflicts detection could not
 *   auto-resolve. The application chooses the backing implementation (Room,
 *   in-memory, or its own) — [DataLoomBuilder] does not select one.
 *
 * ## Scope
 *
 * Enabling this spec affects every registered inbound-pull pipeline
 * (built-in default, and the strategy engine's own canonical pipelines used
 * by all six built-in strategies) uniformly. It does not affect
 * `OutboundPushSynchronizationPipeline` — push has no local-vs-remote
 * comparison to make, matching
 * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator]'s own
 * documented boundary.
 *
 * @param detectors the conflict detectors available for lookup by
 *   [bindings]`.detectorId`. Required, non-empty in practice (an empty
 *   collection means every detection attempt fails with
 *   `DetectorNotFound`).
 * @param resolvers the conflict resolvers available for lookup by
 *   [bindings]`.resolverId`. May be empty when [bindings]`.resolverId` is
 *   `null` (detection-only, no automatic resolution).
 * @param bindings the detector/resolver binding used for every detection
 *   call this configuration enables.
 * @param unresolvedConflictStore the durable store backing
 *   [DurableUnresolvedConflictLog] for conflicts that could not be
 *   auto-resolved.
 * @param unresolvedConflictLogSchemaVersion passed through to
 *   [DurableUnresolvedConflictLog]'s own schema-version parameter.
 * @param unresolvedConflictLogMaximumStateUpdateAttempts passed through to
 *   [DurableUnresolvedConflictLog]'s own retry-bound parameter.
 */
public class DataLoomConflictDetectionSpec(
    public val detectors: Collection<ConflictDetector>,
    public val resolvers: Collection<ConflictResolver>,
    public val bindings: ConflictOrchestrationBindings,
    public val unresolvedConflictStore: DurableStateStore<ConflictId, UnresolvedConflictRecord>,
    public val unresolvedConflictLogSchemaVersion: Int = 1,
    public val unresolvedConflictLogMaximumStateUpdateAttempts: Int = 8,
)
