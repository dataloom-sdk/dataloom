package io.dataloom.runtime.facade

import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
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
 * - [detectors]/[resolvers]: optional application implementations of the same
 *   [io.dataloom.api.conflict.ConflictDetector] and
 *   [io.dataloom.api.conflict.ConflictResolver] contracts the orchestrator
 *   accepts. Exact-ID reference detector/resolver catalogs are also available
 *   through the registries; applications can therefore leave either collection
 *   empty when [bindings] selects a documented reference ID. An application
 *   registration under a reference ID explicitly overrides that reference
 *   implementation.
 * - [bindings]: which detector (required) and resolver (optional) to use
 *   for every detection call. One binding applies to the whole pipeline
 *   instance. Which *resolver* handles a given conflict can vary by entity
 *   type, workflow, and tenant through the binding's optional
 *   [io.dataloom.runtime.conflict.ConflictResolverSelectionPolicy]
 *   (`ConflictOrchestrationBindings.resolverSelectionPolicy`), with strict
 *   precedence entity type > workflow > tenant > the binding's own
 *   `resolverId` as the global default. The detector is still one per
 *   pipeline instance. Without a policy, behavior is exactly the
 *   single-resolver-ID selection this spec has always had. The tenant tier
 *   applies only when the host populated `ExecutionContext.tenantId`.
 *   Resolvers named by the policy are supplied through [resolvers] (or are
 *   built-in reference IDs) like any other; a policy naming an ID with no
 *   resolver yields the same unresolved-conflict outcome as an unknown
 *   single resolver ID, not an exception.
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
 * @param detectors application conflict detectors available for exact-ID
 *   lookup. May be empty when [bindings]`.detectorId` selects a documented
 *   built-in detector. A supplied detector with the same ID overrides the
 *   reference implementation.
 * @param resolvers application conflict resolvers available for exact-ID
 *   lookup. May be empty when no resolver is selected or when
 *   [bindings]`.resolverId` selects a documented built-in resolver. A supplied
 *   resolver with the same ID overrides the reference implementation.
 * @param bindings the detector/resolver binding used for every detection
 *   call this configuration enables.
 * @param unresolvedConflictStore the durable store backing
 *   [DurableUnresolvedConflictLog] for conflicts that could not be
 *   auto-resolved.
 * @param unresolvedConflictLogSchemaVersion passed through to
 *   [DurableUnresolvedConflictLog]'s own schema-version parameter.
 * @param unresolvedConflictLogMaximumStateUpdateAttempts passed through to
 *   [DurableUnresolvedConflictLog]'s own retry-bound parameter.
 * @param resolvedConflictDecisionStore optional durable store backing
 *   [DurableResolvedConflictDecisionLog] for conflicts a resolver genuinely
 *   resolved. `null` (the default) disables resolved-decision recording
 *   entirely — [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator]
 *   still records unresolved outcomes as before. The application chooses the
 *   backing implementation, matching [unresolvedConflictStore]'s own posture.
 * @param resolvedConflictDecisionLogSchemaVersion passed through to
 *   [DurableResolvedConflictDecisionLog]'s own schema-version parameter.
 * @param resolvedConflictDecisionLogMaximumStateUpdateAttempts passed
 *   through to [DurableResolvedConflictDecisionLog]'s own retry-bound
 *   parameter.
 * @param quarantine optional loop/non-convergence quarantine. `null` (the
 *   default) leaves behavior exactly as it was. When supplied, every detected
 *   conflict is counted per entity; the occurrence that reaches the policy
 *   threshold (default 5) is not resolved -- it blocks application and
 *   checkpoint advancement like `Defer`/`Fail` -- and neither is any later
 *   occurrence until an authorized release
 *   ([DataLoomConflictAdministration.releaseQuarantine]). Requires
 *   [resolvedConflictDecisionStore]: quarantine's effect is blocking
 *   application, which only exists when resolved decisions are applied.
 */
public class DataLoomConflictDetectionSpec(
    public val detectors: Collection<ConflictDetector>,
    public val resolvers: Collection<ConflictResolver>,
    public val bindings: ConflictOrchestrationBindings,
    public val unresolvedConflictStore: DurableStateStore<ConflictId, UnresolvedConflictRecord>,
    public val unresolvedConflictLogSchemaVersion: Int = 1,
    public val unresolvedConflictLogMaximumStateUpdateAttempts: Int = 8,
    public val resolvedConflictDecisionStore: DurableStateStore<ConflictId, ResolvedConflictDecisionRecord>? = null,
    public val resolvedConflictDecisionLogSchemaVersion: Int = 1,
    public val resolvedConflictDecisionLogMaximumStateUpdateAttempts: Int = 8,
    public val quarantine: DataLoomConflictQuarantineSpec? = null,
) {
    init {
        require(quarantine == null || resolvedConflictDecisionStore != null) {
            "DataLoomConflictDetectionSpec quarantine requires resolvedConflictDecisionStore: " +
                "quarantine only takes effect when inbound pull applies resolved decisions."
        }
    }
}
