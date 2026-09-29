package io.dataloom.runtime.observation.retry

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.AuthorizedConflictAdministrationCommand
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationDecision
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationId
import io.dataloom.api.conflict.ConflictAdministrationAuthorizer
import io.dataloom.api.conflict.ConflictAdministrationCommandId
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetRequest
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetResult
import io.dataloom.api.conflict.ConflictAdministrationExecutionResult
import io.dataloom.api.conflict.ConflictAdministrationExecutor
import io.dataloom.api.conflict.ConflictAdministrationLoadResult
import io.dataloom.api.conflict.ConflictAdministrationPrincipalId
import io.dataloom.api.conflict.ConflictAdministrationReason
import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.api.conflict.ConflictAdministrationStateStore
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineRecord
import io.dataloom.api.conflict.ConflictQuarantineReleaseRequest
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableConflictQuarantineLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.conflict.ConflictAdministrationCoordinator
import io.dataloom.runtime.conflict.ConflictDetectorRegistry
import io.dataloom.runtime.conflict.ConflictOrchestrationBindings
import io.dataloom.runtime.conflict.ConflictOrchestrationRequest
import io.dataloom.runtime.conflict.ConflictQuarantineReleaseResult
import io.dataloom.runtime.conflict.ConflictQuarantineTracker
import io.dataloom.runtime.conflict.ConflictResolverRegistry
import io.dataloom.runtime.conflict.ConflictResolverSelectionPolicy
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule
import io.dataloom.runtime.conflict.ConflictResolverSelectionTier
import io.dataloom.runtime.conflict.SynchronizationConflictOrchestrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Table-driven proof that [ObservedSynchronizationConflictOrchestrator] and
 * [ObservedConflictAdministrationCoordinator] emit exactly the closed set of
 * conflict signals through the unchanged [BoundedRetryCircuitTelemetry]
 * pipeline, with bounded-cardinality dimensions only.
 */
class ObservedConflictRuntimeTest {

    private val entityType = EntityType("invoice")
    private val detectorId = ConflictDetectorId("test.detector")
    private val resolverId = ConflictResolverId("test.resolver")
    private val workflowId = WorkflowId("workflow-conflict-metrics")

    private fun entity(id: String) = EntityReference(entityType, EntityId(id))

    private fun localEvent(id: String) = ChangeEvent(ChangeEventId("local-$id"), entity(id), ChangeOperation.UPDATE)
    private fun remoteEvent(id: String) = ChangeEvent(ChangeEventId("remote-$id"), entity(id), ChangeOperation.UPDATE)

    private fun request(tenantId: TenantId? = null): SynchronizationRequest = SynchronizationRequest(
        workflowId = workflowId,
        sessionId = SynchronizationSessionId("session-conflict-metrics"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-conflict-metrics"),
            correlationId = CorrelationId("correlation-conflict-metrics"),
            tenantId = tenantId,
        ),
    )

    private class FixedResultDetector(
        override val id: ConflictDetectorId,
        private val result: (ConflictDetectionRequest) -> ConflictDetectionResult,
    ) : ConflictDetector {
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult = result(request)
    }

    private fun conflictDetectedDetector(conflictId: String = "conflict-1") = FixedResultDetector(detectorId) { req ->
        ConflictDetectionResult.ConflictDetected(
            SynchronizationConflict(
                id = ConflictId(conflictId),
                type = ConflictType.CONCURRENT_CHANGE,
                entity = req.localChange.entity,
                localChange = req.localChange,
                remoteChange = req.remoteChange,
            ),
        )
    }

    private class FixedDecisionResolver(
        override val id: ConflictResolverId,
        private val decision: ConflictResolutionDecision,
    ) : ConflictResolver {
        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision = decision
    }

    private object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(5_000L)
    }

    private fun telemetry() = BoundedRetryCircuitTelemetry(
        coroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        configuration = RetryCircuitTelemetryConfiguration(),
        exporters = emptyList(),
    )

    private fun orchestrator(
        detector: ConflictDetector,
        resolvers: List<ConflictResolver> = emptyList(),
        quarantineTracker: ConflictQuarantineTracker? = null,
    ) = SynchronizationConflictOrchestrator(
        detectorRegistry = ConflictDetectorRegistry(listOf(detector)),
        resolverRegistry = ConflictResolverRegistry(resolvers),
        quarantineTracker = quarantineTracker,
    )

    private fun counts(telemetry: BoundedRetryCircuitTelemetry): Map<RetryCircuitMetricKey, Long> =
        telemetry.snapshot().metricCounts

    // -------------------------------------------------------------------------
    // Table-driven metric emission per outcome
    // -------------------------------------------------------------------------

    @Test
    fun detectorNotFoundEmitsNoTelemetry() = runTest {
        val telemetry = telemetry()
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector()),
            FixedClock,
            telemetry,
        )
        val missingDetectorId = ConflictDetectorId("does-not-exist")
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                ConflictOrchestrationBindings(missingDetectorId, resolverId),
            ),
        )
        assertTrue(counts(telemetry).isEmpty(), "no conflict occurred; nothing should be recorded")
    }

    @Test
    fun noConflictEmitsNoTelemetry() = runTest {
        val telemetry = telemetry()
        val detector = FixedResultDetector(detectorId) { ConflictDetectionResult.NoConflict }
        val observed = ObservedSynchronizationConflictOrchestrator(orchestrator(detector), FixedClock, telemetry)
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                ConflictOrchestrationBindings(detectorId, resolverId),
            ),
        )
        assertTrue(counts(telemetry).isEmpty())
    }

    @Test
    fun resolverNotConfiguredEmitsDetectedAndUnresolvedWithNoTierHit() = runTest {
        val telemetry = telemetry()
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector()),
            FixedClock,
            telemetry,
        )
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                ConflictOrchestrationBindings(detectorId, resolverId = null),
            ),
        )
        val snapshot = counts(telemetry)
        assertEquals(1L, snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_DETECTED)])
        assertEquals(
            1L,
            snapshot[
                key(
                    RetryCircuitTelemetrySignal.CONFLICT_UNRESOLVED,
                    unresolvedReason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
                ),
            ],
        )
        assertEquals(2, snapshot.size, "no tier-hit: nothing was selected")
    }

    @Test
    fun resolverNotFoundThroughGlobalDefaultEmitsUnresolvedAndGlobalTierHit() = runTest {
        val telemetry = telemetry()
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector()),
            FixedClock,
            telemetry,
        )
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                ConflictOrchestrationBindings(detectorId, resolverId), // resolver never registered
            ),
        )
        val snapshot = counts(telemetry)
        assertEquals(1L, snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_DETECTED)])
        assertEquals(
            1L,
            snapshot[
                key(
                    RetryCircuitTelemetrySignal.CONFLICT_UNRESOLVED,
                    unresolvedReason = UnresolvedConflictReason.RESOLVER_NOT_FOUND,
                ),
            ],
        )
        assertEquals(
            1L,
            snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_RESOLVER_SELECTION_TIER_HIT, tier = ConflictResolverSelectionTier.GLOBAL)],
        )
        assertEquals(3, snapshot.size)
    }

    @Test
    fun resolverNotFoundThroughEntityTypeRuleEmitsEntityTypeTierHit() = runTest {
        val telemetry = telemetry()
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector()),
            FixedClock,
            telemetry,
        )
        val bindings = ConflictOrchestrationBindings(
            detectorId = detectorId,
            resolverId = null,
            resolverSelectionPolicy = ConflictResolverSelectionPolicy(
                listOf(ConflictResolverSelectionRule.ForEntityType(entityType, ConflictResolverId("unregistered"))),
            ),
        )
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                bindings,
            ),
        )
        val snapshot = counts(telemetry)
        assertEquals(
            1L,
            snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_RESOLVER_SELECTION_TIER_HIT, tier = ConflictResolverSelectionTier.ENTITY_TYPE)],
        )
    }

    @Test
    fun resolverNotFoundThroughWorkflowRuleEmitsWorkflowTierHit() = runTest {
        val telemetry = telemetry()
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector()),
            FixedClock,
            telemetry,
        )
        val bindings = ConflictOrchestrationBindings(
            detectorId = detectorId,
            resolverId = null,
            resolverSelectionPolicy = ConflictResolverSelectionPolicy(
                listOf(ConflictResolverSelectionRule.ForWorkflow(workflowId, ConflictResolverId("unregistered"))),
            ),
        )
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                bindings,
            ),
        )
        assertEquals(
            1L,
            counts(telemetry)[key(RetryCircuitTelemetrySignal.CONFLICT_RESOLVER_SELECTION_TIER_HIT, tier = ConflictResolverSelectionTier.WORKFLOW)],
        )
    }

    @Test
    fun resolverNotFoundThroughTenantRuleEmitsTenantTierHit() = runTest {
        val telemetry = telemetry()
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector()),
            FixedClock,
            telemetry,
        )
        val tenantId = TenantId("tenant-1")
        val bindings = ConflictOrchestrationBindings(
            detectorId = detectorId,
            resolverId = null,
            resolverSelectionPolicy = ConflictResolverSelectionPolicy(
                listOf(ConflictResolverSelectionRule.ForTenant(tenantId, ConflictResolverId("unregistered"))),
            ),
        )
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(tenantId = tenantId), localEvent("e1"), remoteEvent("e1")),
                bindings,
            ),
        )
        assertEquals(
            1L,
            counts(telemetry)[key(RetryCircuitTelemetrySignal.CONFLICT_RESOLVER_SELECTION_TIER_HIT, tier = ConflictResolverSelectionTier.TENANT)],
        )
    }

    @Test
    fun resolvedWithUseLocalUseRemoteOrMergeAllEmitResolvedByResolverId() = runTest {
        for (decision in listOf(
            ConflictResolutionDecision.UseLocal(),
            ConflictResolutionDecision.UseRemote(),
            ConflictResolutionDecision.Merge(entity("e1"), remoteEvent("e1")),
        )) {
            val telemetry = telemetry()
            val resolver = FixedDecisionResolver(resolverId, decision)
            val observed = ObservedSynchronizationConflictOrchestrator(
                orchestrator(conflictDetectedDetector(), resolvers = listOf(resolver)),
                FixedClock,
                telemetry,
            )
            observed.detectAndResolve(
                ConflictOrchestrationRequest(
                    ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                    ConflictOrchestrationBindings(detectorId, resolverId),
                ),
            )
            val snapshot = counts(telemetry)
            assertEquals(1L, snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_DETECTED)], "decision=$decision")
            assertEquals(
                1L,
                snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_RESOLVED, resolverId = resolverId)],
                "decision=$decision",
            )
            assertEquals(
                1L,
                snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_RESOLVER_SELECTION_TIER_HIT, tier = ConflictResolverSelectionTier.GLOBAL)],
                "decision=$decision",
            )
        }
    }

    @Test
    fun resolvedWithDeferEmitsDeferredByResolverId() = runTest {
        val telemetry = telemetry()
        val resolver = FixedDecisionResolver(resolverId, ConflictResolutionDecision.Defer())
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector(), resolvers = listOf(resolver)),
            FixedClock,
            telemetry,
        )
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                ConflictOrchestrationBindings(detectorId, resolverId),
            ),
        )
        assertEquals(
            1L,
            counts(telemetry)[key(RetryCircuitTelemetrySignal.CONFLICT_DEFERRED, resolverId = resolverId)],
        )
    }

    @Test
    fun resolvedWithFailEmitsFailedByResolverId() = runTest {
        val telemetry = telemetry()
        val resolver = FixedDecisionResolver(
            resolverId,
            ConflictResolutionDecision.Fail(
                object : io.dataloom.api.error.DataLoomError {
                    override val code = io.dataloom.api.error.ErrorCode("DL-CONFLICT-REJECTED-BY-POLICY")
                    override val category = io.dataloom.api.error.ErrorCategory.CONFLICT
                    override val severity = io.dataloom.api.error.ErrorSeverity.ERROR
                    override val recoverability = io.dataloom.api.error.Recoverability.NON_RECOVERABLE
                    override val message = "rejected"
                    override val cause: Throwable? = null
                },
            ),
        )
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector(), resolvers = listOf(resolver)),
            FixedClock,
            telemetry,
        )
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                ConflictOrchestrationBindings(detectorId, resolverId),
            ),
        )
        assertEquals(
            1L,
            counts(telemetry)[key(RetryCircuitTelemetrySignal.CONFLICT_FAILED, resolverId = resolverId)],
        )
    }

    @Test
    fun quarantinedEmitsQuarantinedSignalAndTierHitWithNoResolverInvocation() = runTest {
        val telemetry = telemetry()
        val tracker = ConflictQuarantineTracker(
            log = DurableConflictQuarantineLog(InMemoryStore()),
            clock = FixedClock,
            policy = ConflictQuarantinePolicy(occurrenceThreshold = 2),
        )
        var resolverInvocations = 0
        val resolver = object : ConflictResolver {
            override val id: ConflictResolverId = resolverId
            override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
                resolverInvocations++
                return ConflictResolutionDecision.UseRemote()
            }
        }
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector(), resolvers = listOf(resolver), quarantineTracker = tracker),
            FixedClock,
            telemetry,
        )
        fun sameEntityRequest() = ConflictOrchestrationRequest(
            ConflictDetectionRequest(request(), localEvent("same-entity"), remoteEvent("same-entity")),
            ConflictOrchestrationBindings(detectorId, resolverId),
        )
        observed.detectAndResolve(sameEntityRequest()) // counted, resolved
        observed.detectAndResolve(sameEntityRequest()) // reaches threshold: quarantined

        assertEquals(1, resolverInvocations)
        val snapshot = counts(telemetry)
        assertEquals(2L, snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_DETECTED)])
        assertEquals(1L, snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_QUARANTINED)])
        assertEquals(
            1L,
            snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_RESOLVED, resolverId = resolverId)],
        )
    }

    @Test
    fun quarantineUnavailableEmitsFailedWithNoResolverDimension() = runTest {
        val telemetry = telemetry()
        val tracker = ConflictQuarantineTracker(
            log = DurableConflictQuarantineLog(FailingStore()),
            clock = FixedClock,
        )
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetector(), quarantineTracker = tracker),
            FixedClock,
            telemetry,
        )
        observed.detectAndResolve(
            ConflictOrchestrationRequest(
                ConflictDetectionRequest(request(), localEvent("e1"), remoteEvent("e1")),
                ConflictOrchestrationBindings(detectorId, resolverId),
            ),
        )
        val snapshot = counts(telemetry)
        assertEquals(1L, snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_FAILED)])
        assertEquals(0L, snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_FAILED, resolverId = resolverId)] ?: 0L)
    }

    // -------------------------------------------------------------------------
    // Label-cardinality: dynamic entity/conflict/change identity never expands
    // the metric-key space; only the closed dimensions do.
    // -------------------------------------------------------------------------

    @Test
    fun metricCardinalityIsBoundedAcrossManyDistinctEntitiesAndConflicts() = runTest {
        val telemetry = telemetry()
        val resolver = FixedDecisionResolver(resolverId, ConflictResolutionDecision.UseRemote())
        val observed = ObservedSynchronizationConflictOrchestrator(
            orchestrator(conflictDetectedDetectorPerCall(), resolvers = listOf(resolver)),
            FixedClock,
            telemetry,
        )
        repeat(500) { index ->
            observed.detectAndResolve(
                ConflictOrchestrationRequest(
                    ConflictDetectionRequest(request(), localEvent("entity-$index"), remoteEvent("entity-$index")),
                    ConflictOrchestrationBindings(detectorId, resolverId),
                ),
            )
        }
        val snapshot = counts(telemetry)
        // Exactly three distinct keys regardless of 500 distinct entities/conflicts:
        // CONFLICT_DETECTED, CONFLICT_RESOLVED(resolverId), and the GLOBAL tier hit.
        assertEquals(3, snapshot.size)
        assertEquals(500L, snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_DETECTED)])
        assertEquals(500L, snapshot[key(RetryCircuitTelemetrySignal.CONFLICT_RESOLVED, resolverId = resolverId)])
    }

    private fun conflictDetectedDetectorPerCall() = FixedResultDetector(detectorId) { req ->
        ConflictDetectionResult.ConflictDetected(
            SynchronizationConflict(
                id = ConflictId("conflict-${req.localChange.id.value}"),
                type = ConflictType.CONCURRENT_CHANGE,
                entity = req.localChange.entity,
                localChange = req.localChange,
                remoteChange = req.remoteChange,
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Quarantine release
    // -------------------------------------------------------------------------

    @Test
    fun releasedEmitsQuarantineReleasedSignal() = runTest {
        val telemetry = telemetry()
        val quarantineStore = InMemoryStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val scope = ConflictQuarantineScope(entityType, EntityId("e1"))
        // Seed a quarantined record directly so release has something to act on.
        val seedingLog = DurableConflictQuarantineLog(quarantineStore, maximumStateUpdateAttempts = 2)
        repeat(5) {
            seedingLog.recordOccurrence(scope, ConflictId("c-$it"), resolverId, FixedClock.now(), ConflictQuarantinePolicy())
        }
        val coordinator = ConflictAdministrationCoordinator(
            clock = FixedClock,
            authorizer = AlwaysAuthorizesReleaseAuthorizer,
            stateStore = UnusedCommandStore,
            executor = UnusedExecutor,
            unresolvedConflictLog = DurableUnresolvedConflictLog(InMemoryStore()),
            resolvedConflictDecisionLog = DurableResolvedConflictDecisionLog(InMemoryStore()),
            quarantineLog = DurableConflictQuarantineLog(quarantineStore),
        )
        val observed = ObservedConflictAdministrationCoordinator(coordinator, FixedClock, telemetry)

        val released = observed.releaseQuarantine(releaseRequest("cmd-1", scope))
        assertEquals(io.dataloom.runtime.conflict.ConflictQuarantineReleaseResult.Released::class, released::class)
        assertEquals(1L, counts(telemetry)[key(RetryCircuitTelemetrySignal.CONFLICT_QUARANTINE_RELEASED)])

        // Replay (AlreadyReleased) must not double-count.
        observed.releaseQuarantine(releaseRequest("cmd-1", scope))
        assertEquals(1L, counts(telemetry)[key(RetryCircuitTelemetrySignal.CONFLICT_QUARANTINE_RELEASED)])
    }

    @Test
    fun deniedReleaseEmitsNoTelemetry() = runTest {
        val telemetry = telemetry()
        val coordinator = ConflictAdministrationCoordinator(
            clock = FixedClock,
            authorizer = object : ConflictAdministrationAuthorizer {
                override suspend fun authorize(request: ConflictAdministrationRequest) =
                    ConflictAdministrationAuthorizationDecision.Denied("NOT_USED")
                // authorizeQuarantineRelease uses the interface default: Denied.
            },
            stateStore = UnusedCommandStore,
            executor = UnusedExecutor,
            unresolvedConflictLog = DurableUnresolvedConflictLog(InMemoryStore()),
            resolvedConflictDecisionLog = DurableResolvedConflictDecisionLog(InMemoryStore()),
            quarantineLog = DurableConflictQuarantineLog(InMemoryStore()),
        )
        val observed = ObservedConflictAdministrationCoordinator(coordinator, FixedClock, telemetry)

        val denied = observed.releaseQuarantine(
            releaseRequest("cmd-1", ConflictQuarantineScope(entityType, EntityId("e1"))),
        )
        assertEquals(
            io.dataloom.runtime.conflict.ConflictQuarantineReleaseResult.AuthorizationDenied::class,
            denied::class,
        )
        assertTrue(counts(telemetry).isEmpty())
    }

    private fun releaseRequest(command: String, scope: ConflictQuarantineScope) = ConflictQuarantineReleaseRequest(
        commandId = ConflictAdministrationCommandId(command),
        entityType = scope.entityType,
        entityId = scope.entityId,
        principalId = ConflictAdministrationPrincipalId("operator"),
        requestedAt = FixedClock.now(),
        reason = ConflictAdministrationReason("upstream fixed"),
    )

    private object AlwaysAuthorizesReleaseAuthorizer : ConflictAdministrationAuthorizer {
        override suspend fun authorize(request: ConflictAdministrationRequest) =
            ConflictAdministrationAuthorizationDecision.Denied("NOT_USED")

        override suspend fun authorizeQuarantineRelease(request: ConflictQuarantineReleaseRequest) =
            ConflictAdministrationAuthorizationDecision.Authorized(ConflictAdministrationAuthorizationId("auth-ok"))
    }

    private object UnusedCommandStore : ConflictAdministrationStateStore {
        override suspend fun load(
            commandId: ConflictAdministrationCommandId,
        ): ProviderOperationResult<ConflictAdministrationLoadResult> = error("not used by release")

        override suspend fun compareAndSet(
            request: ConflictAdministrationCompareAndSetRequest,
        ): ProviderOperationResult<ConflictAdministrationCompareAndSetResult> = error("not used by release")
    }

    private object UnusedExecutor : ConflictAdministrationExecutor {
        override suspend fun execute(
            command: AuthorizedConflictAdministrationCommand,
        ): ConflictAdministrationExecutionResult = error("not used by release")
    }

    private fun key(
        signal: RetryCircuitTelemetrySignal,
        resolverId: ConflictResolverId? = null,
        unresolvedReason: UnresolvedConflictReason? = null,
        tier: ConflictResolverSelectionTier? = null,
    ) = RetryCircuitMetricKey(
        signal = signal,
        scopeKind = null,
        circuitOperationOutcome = null,
        circuitDetail = null,
        conflictResolverId = resolverId,
        conflictUnresolvedReason = unresolvedReason,
        conflictSelectionTier = tier,
    )

    private class InMemoryStore<S : Any, R : Any>(
        private val loadFailure: io.dataloom.api.error.DataLoomError? = null,
        private val compareAndSetFailure: io.dataloom.api.error.DataLoomError? = null,
    ) : DurableStateStore<S, R> {
        private val records = mutableMapOf<S, DurableStateRecord<R>>()

        override suspend fun load(scope: S): ProviderOperationResult<DurableStateLoadResult<R>> {
            loadFailure?.let { return ProviderOperationResult.Failure(it) }
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<S, R>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<R>> {
            compareAndSetFailure?.let { return ProviderOperationResult.Failure(it) }
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private class FailingStore<S : Any, R : Any> : DurableStateStore<S, R> {
        override suspend fun load(scope: S): ProviderOperationResult<DurableStateLoadResult<R>> =
            ProviderOperationResult.Failure(
                object : io.dataloom.api.error.DataLoomError {
                    override val code = io.dataloom.api.error.ErrorCode("QUARANTINE-STORE-DOWN")
                    override val category = io.dataloom.api.error.ErrorCategory.STORAGE
                    override val severity = io.dataloom.api.error.ErrorSeverity.ERROR
                    override val recoverability = io.dataloom.api.error.Recoverability.RECOVERABLE
                    override val message = "down"
                    override val cause: Throwable? = null
                },
            )

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<S, R>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<R>> = error("not reached")
    }
}
