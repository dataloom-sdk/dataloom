package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.SynchronizationConflict
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
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule.ForEntityType
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule.ForTenant
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule.ForWorkflow
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Proves [SynchronizationConflictOrchestrator] builds the selection context
 * from the real request/conflict values and invokes exactly the resolver the
 * [ConflictResolverSelectionPolicy] chose, through the unchanged
 * [ConflictResolverRegistry].
 */
class SynchronizationConflictOrchestratorSelectionPolicyTest {

    private val detectorId = ConflictDetectorId("detector")
    private val invoice = EntityType("invoice")
    private val document = EntityType("document")
    private val workflowA = WorkflowId("workflow-a")
    private val workflowB = WorkflowId("workflow-b")
    private val tenantX = TenantId("tenant-x")
    private val tenantY = TenantId("tenant-y")

    private val entityResolverId = ConflictResolverId("resolver.entity")
    private val workflowResolverId = ConflictResolverId("resolver.workflow")
    private val tenantResolverId = ConflictResolverId("resolver.tenant")
    private val globalResolverId = ConflictResolverId("resolver.global")

    private class RecordingResolver(
        override val id: ConflictResolverId,
        private val decision: ConflictResolutionDecision = ConflictResolutionDecision.UseRemote(),
    ) : ConflictResolver {
        val requests = mutableListOf<ConflictResolutionRequest>()

        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
            requests += request
            return decision
        }
    }

    /** Detects a conflict on whatever pair it is given, so each test controls the entity type. */
    private class AlwaysConflictDetector(override val id: ConflictDetectorId) : ConflictDetector {
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
            ConflictDetectionResult.ConflictDetected(
                SynchronizationConflict(
                    id = ConflictId("conflict-1"),
                    type = ConflictType.CONCURRENT_CHANGE,
                    entity = request.localChange.entity,
                    localChange = request.localChange,
                    remoteChange = request.remoteChange,
                ),
            )
    }

    private val entityResolver = RecordingResolver(entityResolverId)
    private val workflowResolver = RecordingResolver(workflowResolverId)
    private val tenantResolver = RecordingResolver(tenantResolverId)
    private val globalResolver = RecordingResolver(globalResolverId)
    private val allResolvers = listOf(entityResolver, workflowResolver, tenantResolver, globalResolver)

    private fun resetAll() = allResolvers.forEach { it.requests.clear() }

    private val fullPolicy = ConflictResolverSelectionPolicy(
        listOf(
            ForEntityType(invoice, entityResolverId),
            ForWorkflow(workflowA, workflowResolverId),
            ForTenant(tenantX, tenantResolverId),
        ),
    )

    private fun orchestrator(resolvers: Collection<ConflictResolver> = allResolvers) =
        SynchronizationConflictOrchestrator(
            detectorRegistry = ConflictDetectorRegistry(listOf(AlwaysConflictDetector(detectorId))),
            resolverRegistry = ConflictResolverRegistry(resolvers),
        )

    private fun request(
        entityType: EntityType,
        workflowId: WorkflowId,
        tenantId: TenantId?,
        policy: ConflictResolverSelectionPolicy?,
        globalId: ConflictResolverId? = globalResolverId,
    ): ConflictOrchestrationRequest {
        val entity = EntityReference(entityType, EntityId("entity-1"))
        val sync = SynchronizationRequest(
            workflowId = workflowId,
            sessionId = SynchronizationSessionId("session"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution"),
                correlationId = CorrelationId("correlation"),
                tenantId = tenantId,
            ),
        )
        return ConflictOrchestrationRequest(
            detectionRequest = ConflictDetectionRequest(
                synchronizationRequest = sync,
                localChange = ChangeEvent(ChangeEventId("local"), entity, ChangeOperation.UPDATE),
                remoteChange = ChangeEvent(ChangeEventId("remote"), entity, ChangeOperation.UPDATE),
            ),
            bindings = ConflictOrchestrationBindings(detectorId, globalId, policy),
        )
    }

    private class Case(
        val name: String,
        val entityType: EntityType,
        val workflowId: WorkflowId,
        val tenantId: TenantId?,
        val expected: ConflictResolverId,
    )

    // Context values are chosen so that a tier matches exactly when its key is listed.
    private val cases = listOf(
        Case("entity+workflow+tenant match -> entity", invoice, workflowA, tenantX, entityResolverId),
        Case("entity+workflow match -> entity", invoice, workflowA, tenantY, entityResolverId),
        Case("entity+tenant match -> entity", invoice, workflowB, tenantX, entityResolverId),
        Case("entity only -> entity", invoice, workflowB, tenantY, entityResolverId),
        Case("entity match, tenant absent -> entity", invoice, workflowB, null, entityResolverId),
        Case("workflow+tenant match -> workflow", document, workflowA, tenantX, workflowResolverId),
        Case("workflow only -> workflow", document, workflowA, tenantY, workflowResolverId),
        Case("workflow match, tenant absent -> workflow", document, workflowA, null, workflowResolverId),
        Case("tenant only -> tenant", document, workflowB, tenantX, tenantResolverId),
        Case("nothing matches -> global", document, workflowB, tenantY, globalResolverId),
        Case("nothing matches, tenant absent -> global", document, workflowB, null, globalResolverId),
    )

    @Test
    fun exactlyTheSelectedResolverIsInvoked_forEveryTierCombination() {
        for (case in cases) {
            resetAll()
            val result = runSuspend {
                orchestrator().detectAndResolve(
                    request(case.entityType, case.workflowId, case.tenantId, fullPolicy),
                )
            }
            val resolved = assertIs<ConflictOrchestrationResult.Resolved>(result, case.name)
            assertEquals(case.expected, resolved.resolverId, case.name)
            for (resolver in allResolvers) {
                assertEquals(
                    if (resolver.id == case.expected) 1 else 0,
                    resolver.requests.size,
                    "${case.name}: invocations of ${resolver.id.value}",
                )
            }
        }
    }

    @Test
    fun selectedResolver_receivesTheOriginalRequestAndConflict() {
        resetAll()
        val orchestrationRequest = request(invoice, workflowB, tenantY, fullPolicy)
        val resolved = assertIs<ConflictOrchestrationResult.Resolved>(
            runSuspend { orchestrator().detectAndResolve(orchestrationRequest) },
        )
        val received = entityResolver.requests.single()
        assertSame(orchestrationRequest.detectionRequest.synchronizationRequest, received.synchronizationRequest)
        assertEquals(resolved.conflict, received.conflict)
    }

    @Test
    fun tenantFromExecutionContext_reachesSelection() {
        resetAll()
        val policy = ConflictResolverSelectionPolicy(listOf(ForTenant(tenantX, tenantResolverId)))
        runSuspend { orchestrator().detectAndResolve(request(document, workflowB, tenantX, policy)) }
        assertEquals(1, tenantResolver.requests.size)
        assertEquals(0, globalResolver.requests.size)

        resetAll()
        runSuspend { orchestrator().detectAndResolve(request(document, workflowB, tenantId = null, policy = policy)) }
        assertEquals(0, tenantResolver.requests.size)
        assertEquals(1, globalResolver.requests.size)
    }

    // -------------------------------------------------------------------------
    // Registry semantics are unchanged for a policy-chosen ID
    // -------------------------------------------------------------------------

    @Test
    fun policyNamingAnUnknownResolverId_isResolverNotFound_notAnException() {
        resetAll()
        val unknown = ConflictResolverId("resolver.unknown")
        val policy = ConflictResolverSelectionPolicy(listOf(ForEntityType(invoice, unknown)))
        val result = runSuspend {
            orchestrator().detectAndResolve(request(invoice, workflowA, tenantX, policy))
        }
        val notFound = assertIs<ConflictOrchestrationResult.ResolverNotFound>(result)
        assertEquals(unknown, notFound.resolverId)
        // The registry did not silently fall back to the global default or a lower tier.
        assertEquals(0, allResolvers.sumOf { it.requests.size })
    }

    @Test
    fun noRuleMatches_andNoGlobalDefault_isResolverNotConfigured() {
        resetAll()
        val policy = ConflictResolverSelectionPolicy(listOf(ForEntityType(invoice, entityResolverId)))
        val result = runSuspend {
            orchestrator().detectAndResolve(request(document, workflowA, tenantX, policy, globalId = null))
        }
        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result)
        assertEquals(0, allResolvers.sumOf { it.requests.size })
    }

    @Test
    fun applicationRegistration_stillOverridesBuiltIn_forPolicyChosenId() {
        val serverWinsId = ConflictResolverId("dataloom.builtin.server-wins")
        val policy = ConflictResolverSelectionPolicy(listOf(ForEntityType(invoice, serverWinsId)))

        // No application registration: the built-in (UseRemote) answers.
        val builtIn = assertIs<ConflictOrchestrationResult.Resolved>(
            runSuspend {
                orchestrator(resolvers = emptyList()).detectAndResolve(request(invoice, workflowA, tenantX, policy))
            },
        )
        assertEquals(serverWinsId, builtIn.resolverId)
        assertIs<ConflictResolutionDecision.UseRemote>(builtIn.decision)

        // An application registration under the same ID replaces it, as it always has.
        val override = RecordingResolver(serverWinsId, ConflictResolutionDecision.UseLocal())
        val overridden = assertIs<ConflictOrchestrationResult.Resolved>(
            runSuspend {
                orchestrator(resolvers = listOf(override)).detectAndResolve(request(invoice, workflowA, tenantX, policy))
            },
        )
        assertEquals(1, override.requests.size)
        assertIs<ConflictResolutionDecision.UseLocal>(overridden.decision)
    }

    // -------------------------------------------------------------------------
    // No policy == legacy exact-ID behaviour
    // -------------------------------------------------------------------------

    @Test
    fun withoutPolicy_theSingleBoundResolverHandlesEveryConflict() {
        for ((entityType, workflowId, tenantId) in listOf(
            Triple(invoice, workflowA, tenantX),
            Triple(document, workflowB, tenantY),
            Triple(invoice, workflowB, null),
        )) {
            resetAll()
            val result = runSuspend {
                orchestrator().detectAndResolve(request(entityType, workflowId, tenantId, policy = null))
            }
            assertEquals(globalResolverId, assertIs<ConflictOrchestrationResult.Resolved>(result).resolverId)
            assertEquals(1, globalResolver.requests.size)
            assertEquals(1, allResolvers.sumOf { it.requests.size })
        }
    }

    @Test
    fun withoutPolicy_andNoResolverId_isResolverNotConfigured() {
        val result = runSuspend {
            orchestrator().detectAndResolve(request(invoice, workflowA, tenantX, policy = null, globalId = null))
        }
        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result)
    }

    @Test
    fun emptyPolicy_producesTheSameResultAsNoPolicy() {
        resetAll()
        val withEmpty = runSuspend {
            orchestrator().detectAndResolve(
                request(invoice, workflowA, tenantX, ConflictResolverSelectionPolicy(emptyList())),
            )
        }
        val withNone = runSuspend {
            orchestrator().detectAndResolve(request(invoice, workflowA, tenantX, policy = null))
        }
        assertEquals(withNone, withEmpty)
    }

    // -------------------------------------------------------------------------

    private object Pending

    private fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) rawResult = result.getOrNull() else thrown = result.exceptionOrNull()
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending) { "Suspend block did not complete synchronously in test." }
        @Suppress("UNCHECKED_CAST")
        return rawResult as T
    }
}
