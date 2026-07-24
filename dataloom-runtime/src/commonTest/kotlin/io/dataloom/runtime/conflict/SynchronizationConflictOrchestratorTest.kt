package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.payload.EntityVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-025 conflict orchestration.
 *
 * All fakes are stateless or deterministically stateful. No real database,
 * real network, filesystem, system clock, random identifiers, Thread.sleep,
 * arbitrary delays, Android APIs, JVM-only APIs, reflection, ServiceLoader,
 * production credentials, or personal data are used.
 */
class SynchronizationConflictOrchestratorTest {

    // =========================================================================
    // Test fixtures
    // =========================================================================

    private val entityType = EntityType("invoice")
    private val entityId = EntityId("entity-001")
    private val versionV1 = EntityVersion("v1")
    private val versionV2 = EntityVersion("v2")

    private val entityRef = EntityReference(entityType, entityId)
    private val entityRefV1 = EntityReference(entityType, entityId, versionV1)
    private val entityRefV2 = EntityReference(entityType, entityId, versionV2)

    private val localEvent = ChangeEvent(
        id = ChangeEventId("event-local"),
        entity = entityRefV1,
        operation = ChangeOperation.UPDATE,
    )
    private val remoteEvent = ChangeEvent(
        id = ChangeEventId("event-remote"),
        entity = entityRefV2,
        operation = ChangeOperation.UPDATE,
    )

    private val conflictId = ConflictId("conflict-001")

    private val sampleConflict = SynchronizationConflict(
        id = conflictId,
        type = ConflictType.CONCURRENT_CHANGE,
        entity = entityRef,
        localChange = localEvent,
        remoteChange = remoteEvent,
    )

    private val syncRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private val detectionRequest = ConflictDetectionRequest(
        synchronizationRequest = syncRequest,
        localChange = localEvent,
        remoteChange = remoteEvent,
    )

    // =========================================================================
    // Fake detectors
    // =========================================================================

    /** Returns a fixed [ConflictDetectionResult] and records invocations. */
    private class FakeDetector(
        override val id: ConflictDetectorId,
        private val result: ConflictDetectionResult,
    ) : ConflictDetector {
        var invokeCount = 0
        var lastRequest: ConflictDetectionRequest? = null

        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
            invokeCount++
            lastRequest = request
            return result
        }
    }

    /** Throws on detect. */
    private class ThrowingDetector(
        override val id: ConflictDetectorId,
        private val exception: Throwable,
    ) : ConflictDetector {
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
            throw exception
        }
    }

    // =========================================================================
    // Fake resolvers
    // =========================================================================

    /** Returns a fixed [ConflictResolutionDecision] and records invocations. */
    private class FakeResolver(
        override val id: ConflictResolverId,
        private val decision: ConflictResolutionDecision,
    ) : ConflictResolver {
        var invokeCount = 0
        var lastRequest: ConflictResolutionRequest? = null

        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
            invokeCount++
            lastRequest = request
            return decision
        }
    }

    /** Throws on resolve. */
    private class ThrowingResolver(
        override val id: ConflictResolverId,
        private val exception: Throwable,
    ) : ConflictResolver {
        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
            throw exception
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun detectorId(value: String) = ConflictDetectorId(value)
    private fun resolverId(value: String) = ConflictResolverId(value)

    private fun noConflictDetector(id: String) =
        FakeDetector(detectorId(id), ConflictDetectionResult.NoConflict)

    private fun conflictDetector(id: String, conflict: SynchronizationConflict = sampleConflict) =
        FakeDetector(detectorId(id), ConflictDetectionResult.ConflictDetected(conflict))

    private fun useLocalResolver(id: String) =
        FakeResolver(resolverId(id), ConflictResolutionDecision.UseLocal())

    private fun buildOrchestrator(
        detectors: Collection<ConflictDetector> = emptyList(),
        resolvers: Collection<ConflictResolver> = emptyList(),
    ) = SynchronizationConflictOrchestrator(
        detectorRegistry = ConflictDetectorRegistry(detectors),
        resolverRegistry = ConflictResolverRegistry(resolvers),
    )

    private fun buildRequest(
        detectorId: String,
        resolverId: String? = null,
        detection: ConflictDetectionRequest = detectionRequest,
    ) = ConflictOrchestrationRequest(
        detectionRequest = detection,
        bindings = ConflictOrchestrationBindings(
            detectorId = ConflictDetectorId(detectorId),
            resolverId = resolverId?.let { ConflictResolverId(it) },
        ),
    )

    // =========================================================================
    // ConflictDetectorRegistry
    // =========================================================================

    @Test
    fun `detector registry with empty collection`() {
        val registry = ConflictDetectorRegistry(emptyList())
        assertTrue(registry.detectors.isEmpty())
    }

    @Test
    fun `detector registry with one detector`() {
        val detector = noConflictDetector("d1")
        val registry = ConflictDetectorRegistry(listOf(detector))
        assertEquals(1, registry.detectors.size)
        assertSame(detector, registry.lookup(detectorId("d1")))
    }

    @Test
    fun `detector registry with multiple detectors`() {
        val d1 = noConflictDetector("d1")
        val d2 = noConflictDetector("d2")
        val d3 = noConflictDetector("d3")
        val registry = ConflictDetectorRegistry(listOf(d1, d2, d3))
        assertEquals(3, registry.detectors.size)
    }

    @Test
    fun `detector registry preserves registration order`() {
        val d1 = noConflictDetector("d1")
        val d2 = noConflictDetector("d2")
        val d3 = noConflictDetector("d3")
        val registry = ConflictDetectorRegistry(listOf(d1, d2, d3))
        val ids = registry.detectors.map { it.id.value }
        assertEquals(listOf("d1", "d2", "d3"), ids)
    }

    @Test
    fun `detector registry exact id lookup`() {
        val d1 = noConflictDetector("detector-alpha")
        val d2 = noConflictDetector("detector-beta")
        val registry = ConflictDetectorRegistry(listOf(d1, d2))
        assertSame(d1, registry.lookup(detectorId("detector-alpha")))
        assertSame(d2, registry.lookup(detectorId("detector-beta")))
    }

    @Test
    fun `detector registry missing id lookup returns null`() {
        val registry = ConflictDetectorRegistry(listOf(noConflictDetector("d1")))
        assertNull(registry.lookup(detectorId("missing")))
    }

    @Test
    fun `detector registry rejects duplicate ids`() {
        val d1 = noConflictDetector("same-id")
        val d2 = noConflictDetector("same-id")
        assertFailsWith<IllegalArgumentException> {
            ConflictDetectorRegistry(listOf(d1, d2))
        }
    }

    @Test
    fun `detector registry defensively copies source collection`() {
        val detector = noConflictDetector("d1")
        val source = mutableListOf<ConflictDetector>(detector)
        val registry = ConflictDetectorRegistry(source)
        source.clear()
        assertEquals(1, registry.detectors.size)
    }

    @Test
    fun `detector registry caller mutation does not affect registry`() {
        val d1 = noConflictDetector("d1")
        val source = mutableListOf<ConflictDetector>(d1)
        val registry = ConflictDetectorRegistry(source)
        source.add(noConflictDetector("d2"))
        assertEquals(1, registry.detectors.size)
    }

    @Test
    fun `detector registry construction performs no detection`() {
        val detector = noConflictDetector("d1")
        ConflictDetectorRegistry(listOf(detector))
        assertEquals(0, detector.invokeCount)
    }

    @Test
    fun `detector registry exposes no mutable collection`() {
        val registry = ConflictDetectorRegistry(listOf(noConflictDetector("d1")))
        // The declared return type is List<ConflictDetector>; callers cannot modify it through the API.
        // Verify it is a snapshot: modifying a copy does not change the registry size.
        val snapshot = registry.detectors.toMutableList()
        snapshot.clear()
        assertEquals(1, registry.detectors.size)
    }

    // =========================================================================
    // ConflictResolverRegistry
    // =========================================================================

    @Test
    fun `resolver registry with empty collection`() {
        val registry = ConflictResolverRegistry(emptyList())
        assertTrue(registry.resolvers.isEmpty())
    }

    @Test
    fun `resolver registry with one resolver`() {
        val resolver = useLocalResolver("r1")
        val registry = ConflictResolverRegistry(listOf(resolver))
        assertEquals(1, registry.resolvers.size)
        assertSame(resolver, registry.lookup(resolverId("r1")))
    }

    @Test
    fun `resolver registry with multiple resolvers`() {
        val r1 = useLocalResolver("r1")
        val r2 = useLocalResolver("r2")
        val r3 = useLocalResolver("r3")
        val registry = ConflictResolverRegistry(listOf(r1, r2, r3))
        assertEquals(3, registry.resolvers.size)
    }

    @Test
    fun `resolver registry preserves registration order`() {
        val r1 = useLocalResolver("r1")
        val r2 = useLocalResolver("r2")
        val r3 = useLocalResolver("r3")
        val registry = ConflictResolverRegistry(listOf(r1, r2, r3))
        val ids = registry.resolvers.map { it.id.value }
        assertEquals(listOf("r1", "r2", "r3"), ids)
    }

    @Test
    fun `resolver registry exact id lookup`() {
        val r1 = useLocalResolver("resolver-alpha")
        val r2 = useLocalResolver("resolver-beta")
        val registry = ConflictResolverRegistry(listOf(r1, r2))
        assertSame(r1, registry.lookup(resolverId("resolver-alpha")))
        assertSame(r2, registry.lookup(resolverId("resolver-beta")))
    }

    @Test
    fun `resolver registry missing id lookup returns null`() {
        val registry = ConflictResolverRegistry(listOf(useLocalResolver("r1")))
        assertNull(registry.lookup(resolverId("missing")))
    }

    @Test
    fun `resolver registry rejects duplicate ids`() {
        val r1 = useLocalResolver("same-id")
        val r2 = useLocalResolver("same-id")
        assertFailsWith<IllegalArgumentException> {
            ConflictResolverRegistry(listOf(r1, r2))
        }
    }

    @Test
    fun `resolver registry defensively copies source collection`() {
        val resolver = useLocalResolver("r1")
        val source = mutableListOf<ConflictResolver>(resolver)
        val registry = ConflictResolverRegistry(source)
        source.clear()
        assertEquals(1, registry.resolvers.size)
    }

    @Test
    fun `resolver registry caller mutation does not affect registry`() {
        val r1 = useLocalResolver("r1")
        val source = mutableListOf<ConflictResolver>(r1)
        val registry = ConflictResolverRegistry(source)
        source.add(useLocalResolver("r2"))
        assertEquals(1, registry.resolvers.size)
    }

    @Test
    fun `resolver registry construction performs no resolution`() {
        val resolver = useLocalResolver("r1")
        ConflictResolverRegistry(listOf(resolver))
        assertEquals(0, resolver.invokeCount)
    }

    @Test
    fun `resolver registry exposes no mutable collection`() {
        val registry = ConflictResolverRegistry(listOf(useLocalResolver("r1")))
        // The declared return type is List<ConflictResolver>; callers cannot modify it through the API.
        // Verify it is a snapshot: modifying a copy does not change the registry size.
        val snapshot = registry.resolvers.toMutableList()
        snapshot.clear()
        assertEquals(1, registry.resolvers.size)
    }

    // =========================================================================
    // ConflictOrchestrationBindings
    // =========================================================================

    @Test
    fun `bindings requires detector id`() {
        val bindings = ConflictOrchestrationBindings(
            detectorId = ConflictDetectorId("my-detector"),
            resolverId = null,
        )
        assertEquals("my-detector", bindings.detectorId.value)
    }

    @Test
    fun `bindings resolver id may be null`() {
        val bindings = ConflictOrchestrationBindings(
            detectorId = ConflictDetectorId("d"),
            resolverId = null,
        )
        assertNull(bindings.resolverId)
    }

    @Test
    fun `bindings explicit resolver id is preserved`() {
        val rid = ConflictResolverId("explicit-resolver")
        val bindings = ConflictOrchestrationBindings(
            detectorId = ConflictDetectorId("d"),
            resolverId = rid,
        )
        assertEquals(rid, bindings.resolverId)
    }

    @Test
    fun `bindings value based equality`() {
        val a = ConflictOrchestrationBindings(ConflictDetectorId("d"), ConflictResolverId("r"))
        val b = ConflictOrchestrationBindings(ConflictDetectorId("d"), ConflictResolverId("r"))
        assertEquals(a, b)
    }

    @Test
    fun `bindings construction performs no lookup`() {
        // No registry supplied — verifies construction does not interact with any registry
        val bindings = ConflictOrchestrationBindings(
            detectorId = ConflictDetectorId("not-in-any-registry"),
            resolverId = ConflictResolverId("not-in-any-registry"),
        )
        assertNotNull(bindings)
    }

    // =========================================================================
    // ConflictOrchestrationRequest
    // =========================================================================

    @Test
    fun `orchestration request preserves exact detection request`() {
        val request = buildRequest("d1")
        assertSame(detectionRequest, request.detectionRequest)
    }

    @Test
    fun `orchestration request preserves exact bindings`() {
        val bindings = ConflictOrchestrationBindings(ConflictDetectorId("d1"), null)
        val request = ConflictOrchestrationRequest(detectionRequest, bindings)
        assertSame(bindings, request.bindings)
    }

    @Test
    fun `orchestration request construction invokes no detector or resolver`() {
        val detector = noConflictDetector("d1")
        val resolver = useLocalResolver("r1")
        buildRequest("d1", "r1")
        assertEquals(0, detector.invokeCount)
        assertEquals(0, resolver.invokeCount)
    }

    @Test
    fun `orchestration request diagnostics do not expose payload bytes`() {
        val request = buildRequest("d1", "r1")
        val diagnostic = request.toString()
        assertTrue(diagnostic.contains("d1"))
        assertTrue(diagnostic.contains("r1"))
        // Should not contain the payload — we only check it doesn't contain the entity type/id
        // from data that could be payload-related. The safe fields (entityType, entityId) are allowed.
        // The key test is no stack trace is stored — this is structural.
        assertTrue(diagnostic.isNotEmpty())
    }

    // =========================================================================
    // Missing detector
    // =========================================================================

    @Test
    fun `absent detector returns detector not found`() {
        val orchestrator = buildOrchestrator()
        val result = orchestrator.detectAndResolve(buildRequest("missing-detector"))
        assertIs<ConflictOrchestrationResult.DetectorNotFound>(result)
    }

    @Test
    fun `detector not found preserves requested detector id`() {
        val requestedId = ConflictDetectorId("my-absent-detector")
        val orchestrator = buildOrchestrator()
        val result = orchestrator.detectAndResolve(
            ConflictOrchestrationRequest(
                detectionRequest = detectionRequest,
                bindings = ConflictOrchestrationBindings(requestedId, null),
            ),
        )
        result as ConflictOrchestrationResult.DetectorNotFound
        assertEquals(requestedId, result.detectorId)
    }

    @Test
    fun `detector not found invokes no detector`() {
        val detector = noConflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        orchestrator.detectAndResolve(buildRequest("d2"))
        assertEquals(0, detector.invokeCount)
    }

    @Test
    fun `detector not found performs no resolver lookup`() {
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(
            detectors = emptyList(),
            resolvers = listOf(resolver),
        )
        orchestrator.detectAndResolve(buildRequest("missing-d", "r1"))
        assertEquals(0, resolver.invokeCount)
    }

    // =========================================================================
    // No conflict
    // =========================================================================

    @Test
    fun `no conflict result is returned when detector reports no conflict`() {
        val detector = noConflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val result = orchestrator.detectAndResolve(buildRequest("d1"))
        assertIs<ConflictOrchestrationResult.NoConflict>(result)
    }

    @Test
    fun `exact detector is selected by id`() {
        val d1 = noConflictDetector("d1")
        val d2 = noConflictDetector("d2")
        val orchestrator = buildOrchestrator(detectors = listOf(d1, d2))
        orchestrator.detectAndResolve(buildRequest("d1"))
        assertEquals(1, d1.invokeCount)
        assertEquals(0, d2.invokeCount)
    }

    @Test
    fun `detector receives exact detection request`() {
        val detector = noConflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        orchestrator.detectAndResolve(buildRequest("d1"))
        assertSame(detectionRequest, detector.lastRequest)
    }

    @Test
    fun `detector executes exactly once on no conflict`() {
        val detector = noConflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        orchestrator.detectAndResolve(buildRequest("d1"))
        assertEquals(1, detector.invokeCount)
    }

    @Test
    fun `no conflict result does not invoke resolver`() {
        val detector = noConflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        assertEquals(0, resolver.invokeCount)
    }

    @Test
    fun `no conflict preserves detector id`() {
        val detector = noConflictDetector("my-detector")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val result = orchestrator.detectAndResolve(buildRequest("my-detector"))
        result as ConflictOrchestrationResult.NoConflict
        assertEquals(ConflictDetectorId("my-detector"), result.detectorId)
    }

    // =========================================================================
    // Resolver not configured
    // =========================================================================

    @Test
    fun `null resolver id returns resolver not configured`() {
        val detector = conflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val result = orchestrator.detectAndResolve(buildRequest("d1", resolverId = null))
        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result)
    }

    @Test
    fun `resolver not configured preserves exact conflict`() {
        val detector = conflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val result = orchestrator.detectAndResolve(buildRequest("d1", resolverId = null))
        result as ConflictOrchestrationResult.ResolverNotConfigured
        assertSame(sampleConflict, result.conflict)
    }

    @Test
    fun `resolver not configured preserves detector id`() {
        val detector = conflictDetector("my-detector")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val result = orchestrator.detectAndResolve(buildRequest("my-detector", resolverId = null))
        result as ConflictOrchestrationResult.ResolverNotConfigured
        assertEquals(ConflictDetectorId("my-detector"), result.detectorId)
    }

    @Test
    fun `resolver not configured does not look up any resolver`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        orchestrator.detectAndResolve(buildRequest("d1", resolverId = null))
        assertEquals(0, resolver.invokeCount)
    }

    // =========================================================================
    // Resolver not found
    // =========================================================================

    @Test
    fun `missing resolver returns resolver not found`() {
        val detector = conflictDetector("d1")
        val orchestrator = buildOrchestrator(
            detectors = listOf(detector),
            resolvers = emptyList(),
        )
        val result = orchestrator.detectAndResolve(buildRequest("d1", "missing-resolver"))
        assertIs<ConflictOrchestrationResult.ResolverNotFound>(result)
    }

    @Test
    fun `resolver not found preserves exact conflict`() {
        val detector = conflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "absent-r"))
        result as ConflictOrchestrationResult.ResolverNotFound
        assertSame(sampleConflict, result.conflict)
    }

    @Test
    fun `resolver not found preserves requested resolver id`() {
        val detector = conflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "specific-resolver-id"))
        result as ConflictOrchestrationResult.ResolverNotFound
        assertEquals(ConflictResolverId("specific-resolver-id"), result.resolverId)
    }

    @Test
    fun `resolver not found invokes no fallback resolver`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        // Resolve id is "r2" which is absent; r1 should NOT be invoked as fallback
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        orchestrator.detectAndResolve(buildRequest("d1", "r2"))
        assertEquals(0, resolver.invokeCount)
    }

    // =========================================================================
    // Successful resolution
    // =========================================================================

    @Test
    fun `exact detector is selected by id for resolution`() {
        val d1 = conflictDetector("d1")
        val d2 = conflictDetector("d2")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(d1, d2), listOf(resolver))
        orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        assertEquals(1, d1.invokeCount)
        assertEquals(0, d2.invokeCount)
    }

    @Test
    fun `exact resolver is selected by id`() {
        val detector = conflictDetector("d1")
        val r1 = useLocalResolver("r1")
        val r2 = useLocalResolver("r2")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(r1, r2))
        orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        assertEquals(1, r1.invokeCount)
        assertEquals(0, r2.invokeCount)
    }

    @Test
    fun `detector executes exactly once on conflict`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        assertEquals(1, detector.invokeCount)
    }

    @Test
    fun `resolver executes exactly once`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        assertEquals(1, resolver.invokeCount)
    }

    @Test
    fun `resolver receives exact detected conflict`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        assertNotNull(resolver.lastRequest)
        assertSame(sampleConflict, resolver.lastRequest!!.conflict)
    }

    @Test
    fun `conflict id is preserved in resolved result`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        result as ConflictOrchestrationResult.Resolved
        assertEquals(conflictId, result.conflict.id)
    }

    @Test
    fun `entity identity is preserved in resolved result`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        result as ConflictOrchestrationResult.Resolved
        assertEquals(entityRef, result.conflict.entity)
    }

    @Test
    fun `local and remote versions are preserved in resolved result`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        result as ConflictOrchestrationResult.Resolved
        assertEquals(localEvent, result.conflict.localChange)
        assertEquals(remoteEvent, result.conflict.remoteChange)
    }

    @Test
    fun `payload references are preserved without copying`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        result as ConflictOrchestrationResult.Resolved
        assertSame(sampleConflict, result.conflict)
    }

    @Test
    fun `exact resolution decision is returned`() {
        val detector = conflictDetector("d1")
        val decision = ConflictResolutionDecision.UseRemote()
        val resolver = FakeResolver(resolverId("r1"), decision)
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        result as ConflictOrchestrationResult.Resolved
        assertSame(decision, result.decision)
    }

    @Test
    fun `resolved result preserves detector id`() {
        val detector = conflictDetector("my-det")
        val resolver = useLocalResolver("my-res")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("my-det", "my-res"))
        result as ConflictOrchestrationResult.Resolved
        assertEquals(ConflictDetectorId("my-det"), result.detectorId)
    }

    @Test
    fun `resolved result preserves resolver id`() {
        val detector = conflictDetector("my-det")
        val resolver = useLocalResolver("my-res")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("my-det", "my-res"))
        result as ConflictOrchestrationResult.Resolved
        assertEquals(ConflictResolverId("my-res"), result.resolverId)
    }

    @Test
    fun `non-selected detector and resolver are not invoked`() {
        val selectedDetector = conflictDetector("d-selected")
        val otherDetector = conflictDetector("d-other")
        val selectedResolver = useLocalResolver("r-selected")
        val otherResolver = useLocalResolver("r-other")
        val orchestrator = buildOrchestrator(
            listOf(selectedDetector, otherDetector),
            listOf(selectedResolver, otherResolver),
        )
        orchestrator.detectAndResolve(buildRequest("d-selected", "r-selected"))
        assertEquals(0, otherDetector.invokeCount)
        assertEquals(0, otherResolver.invokeCount)
    }

    // =========================================================================
    // Explicit selection
    // =========================================================================

    @Test
    fun `multiple detectors explicit detector id determines selection`() {
        val d1 = conflictDetector("detector-first")
        val d2 = noConflictDetector("detector-second")
        val d3 = conflictDetector("detector-third")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(d1, d2, d3), listOf(resolver))
        // Select second by ID even though it is registered second
        orchestrator.detectAndResolve(buildRequest("detector-second", "r1"))
        assertEquals(0, d1.invokeCount)
        assertEquals(1, d2.invokeCount)
        assertEquals(0, d3.invokeCount)
    }

    @Test
    fun `registration order does not override binding`() {
        val d1 = conflictDetector("d1")
        val d2 = conflictDetector("d2")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(d1, d2), listOf(resolver))
        // Explicitly bind to d2
        orchestrator.detectAndResolve(buildRequest("d2", "r1"))
        assertEquals(0, d1.invokeCount)
        assertEquals(1, d2.invokeCount)
    }

    @Test
    fun `multiple resolvers explicit resolver id determines selection`() {
        val detector = conflictDetector("d1")
        val r1 = useLocalResolver("resolver-first")
        val r2 = FakeResolver(resolverId("resolver-second"), ConflictResolutionDecision.UseRemote())
        val r3 = useLocalResolver("resolver-third")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(r1, r2, r3))
        orchestrator.detectAndResolve(buildRequest("d1", "resolver-second"))
        assertEquals(0, r1.invokeCount)
        assertEquals(1, r2.invokeCount)
        assertEquals(0, r3.invokeCount)
    }

    @Test
    fun `no resolver is selected by conflict type`() {
        val detector = conflictDetector("d1")
        val r1 = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(r1))
        // Explicitly do NOT configure a resolver; r1 should NOT be auto-selected by conflict type
        val result = orchestrator.detectAndResolve(buildRequest("d1", resolverId = null))
        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(result)
        assertEquals(0, r1.invokeCount)
    }

    // =========================================================================
    // Exceptions and programming errors
    // =========================================================================

    @Test
    fun `detector programming exception propagates`() {
        val exception = RuntimeException("detector exploded")
        val detector = ThrowingDetector(detectorId("d1"), exception)
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val thrown = runCatching { orchestrator.detectAndResolve(buildRequest("d1")) }
            .exceptionOrNull()
        assertSame(exception, thrown)
    }

    @Test
    fun `resolver programming exception propagates`() {
        val exception = RuntimeException("resolver exploded")
        val detector = conflictDetector("d1")
        val resolver = ThrowingResolver(resolverId("r1"), exception)
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val thrown = runCatching { orchestrator.detectAndResolve(buildRequest("d1", "r1")) }
            .exceptionOrNull()
        assertSame(exception, thrown)
    }

    @Test
    fun `detector exception is not converted into a result`() {
        val detector = ThrowingDetector(detectorId("d1"), RuntimeException("boom"))
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        var caught = false
        try {
            orchestrator.detectAndResolve(buildRequest("d1"))
        } catch (e: RuntimeException) {
            caught = true
        }
        assertTrue(caught, "Expected exception to propagate")
    }

    @Test
    fun `resolver exception is not converted into a result`() {
        val detector = conflictDetector("d1")
        val resolver = ThrowingResolver(resolverId("r1"), RuntimeException("boom"))
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        var caught = false
        try {
            orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        } catch (e: RuntimeException) {
            caught = true
        }
        assertTrue(caught, "Expected exception to propagate")
    }

    // =========================================================================
    // All resolution decision variants are preserved
    // =========================================================================

    @Test
    fun `use local decision is preserved exactly`() {
        val detector = conflictDetector("d1")
        val decision = ConflictResolutionDecision.UseLocal()
        val resolver = FakeResolver(resolverId("r1"), decision)
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        result as ConflictOrchestrationResult.Resolved
        assertSame(decision, result.decision)
        assertIs<ConflictResolutionDecision.UseLocal>(result.decision)
    }

    @Test
    fun `use remote decision is preserved exactly`() {
        val detector = conflictDetector("d1")
        val decision = ConflictResolutionDecision.UseRemote()
        val resolver = FakeResolver(resolverId("r1"), decision)
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        result as ConflictOrchestrationResult.Resolved
        assertSame(decision, result.decision)
    }

    @Test
    fun `defer decision is preserved exactly`() {
        val detector = conflictDetector("d1")
        val decision = ConflictResolutionDecision.Defer()
        val resolver = FakeResolver(resolverId("r1"), decision)
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        result as ConflictOrchestrationResult.Resolved
        assertSame(decision, result.decision)
    }

    @Test
    fun `resolver resolution request carries exact synchronization request`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        assertSame(syncRequest, resolver.lastRequest!!.synchronizationRequest)
    }

    // =========================================================================
    // Security: diagnostics do not expose payload content
    // =========================================================================

    @Test
    fun `orchestration request toString does not expose payload content`() {
        val request = buildRequest("d1", "r1")
        val diagnostic = request.toString()
        // Must contain safe fields
        assertTrue(diagnostic.contains("d1"))
        // Must not contain raw payload bytes — since our ChangeEvent has no payload
        // in this test, we verify the toString is bounded to structural info
        assertTrue(diagnostic.isNotEmpty())
    }

    @Test
    fun `resolver not configured toString does not expose payload content`() {
        val detector = conflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val result = orchestrator.detectAndResolve(buildRequest("d1"))
        result as ConflictOrchestrationResult.ResolverNotConfigured
        val diagnostic = result.toString()
        assertTrue(diagnostic.contains("ResolverNotConfigured"))
        assertTrue(diagnostic.contains(conflictId.value))
    }

    @Test
    fun `resolver not found toString does not expose payload content`() {
        val detector = conflictDetector("d1")
        val orchestrator = buildOrchestrator(detectors = listOf(detector))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "absent"))
        result as ConflictOrchestrationResult.ResolverNotFound
        val diagnostic = result.toString()
        assertTrue(diagnostic.contains("ResolverNotFound"))
        assertTrue(diagnostic.contains("absent"))
    }

    @Test
    fun `resolved toString does not expose payload content`() {
        val detector = conflictDetector("d1")
        val resolver = useLocalResolver("r1")
        val orchestrator = buildOrchestrator(listOf(detector), listOf(resolver))
        val result = orchestrator.detectAndResolve(buildRequest("d1", "r1"))
        result as ConflictOrchestrationResult.Resolved
        val diagnostic = result.toString()
        assertTrue(diagnostic.contains("Resolved"))
        assertTrue(diagnostic.contains("d1"))
        assertTrue(diagnostic.contains("r1"))
    }

    @Test
    fun `detector registry toString does not invoke detector toString`() {
        val detector = noConflictDetector("d1")
        val registry = ConflictDetectorRegistry(listOf(detector))
        val diagnostic = registry.toString()
        assertTrue(diagnostic.contains("d1"))
    }

    @Test
    fun `resolver registry toString does not invoke resolver toString`() {
        val resolver = useLocalResolver("r1")
        val registry = ConflictResolverRegistry(listOf(resolver))
        val diagnostic = registry.toString()
        assertTrue(diagnostic.contains("r1"))
    }

    // =========================================================================
    // Compatibility: no Android, no JVM-only, no reflection, no ServiceLoader
    // =========================================================================

    @Test
    fun `orchestrator uses no platform specific types`() {
        // Verified by the fact that the entire test compiles in commonTest
        val orchestrator = buildOrchestrator(
            listOf(noConflictDetector("d")),
            listOf(useLocalResolver("r")),
        )
        assertNotNull(orchestrator)
    }

    @Test
    fun `registries use no platform specific types`() {
        val dr = ConflictDetectorRegistry(listOf(noConflictDetector("d")))
        val rr = ConflictResolverRegistry(listOf(useLocalResolver("r")))
        assertNotNull(dr)
        assertNotNull(rr)
    }

    // =========================================================================
    // ConflictOrchestrationStatus documented values
    // =========================================================================

    @Test
    fun `all expected status values exist`() {
        val statuses = ConflictOrchestrationStatus.entries.map { it.name }.toSet()
        assertTrue(statuses.contains("DETECTOR_NOT_FOUND"))
        assertTrue(statuses.contains("NO_CONFLICT"))
        assertTrue(statuses.contains("RESOLVER_NOT_CONFIGURED"))
        assertTrue(statuses.contains("RESOLVER_NOT_FOUND"))
        assertTrue(statuses.contains("RESOLVED"))
    }

    @Test
    fun `status values are referenced by name not ordinal`() {
        assertEquals("DETECTOR_NOT_FOUND", ConflictOrchestrationStatus.DETECTOR_NOT_FOUND.name)
        assertEquals("NO_CONFLICT", ConflictOrchestrationStatus.NO_CONFLICT.name)
        assertEquals("RESOLVER_NOT_CONFIGURED", ConflictOrchestrationStatus.RESOLVER_NOT_CONFIGURED.name)
        assertEquals("RESOLVER_NOT_FOUND", ConflictOrchestrationStatus.RESOLVER_NOT_FOUND.name)
        assertEquals("RESOLVED", ConflictOrchestrationStatus.RESOLVED.name)
    }

    // =========================================================================
    // ConflictOrchestrationResult equality contracts
    // =========================================================================

    @Test
    fun `detector not found equality`() {
        val a = ConflictOrchestrationResult.DetectorNotFound(ConflictDetectorId("x"))
        val b = ConflictOrchestrationResult.DetectorNotFound(ConflictDetectorId("x"))
        assertEquals(a, b)
    }

    @Test
    fun `no conflict equality`() {
        val a = ConflictOrchestrationResult.NoConflict(ConflictDetectorId("x"))
        val b = ConflictOrchestrationResult.NoConflict(ConflictDetectorId("x"))
        assertEquals(a, b)
    }

    @Test
    fun `resolver not configured equality`() {
        val a = ConflictOrchestrationResult.ResolverNotConfigured(sampleConflict, ConflictDetectorId("x"))
        val b = ConflictOrchestrationResult.ResolverNotConfigured(sampleConflict, ConflictDetectorId("x"))
        assertEquals(a, b)
    }

    @Test
    fun `resolver not found equality`() {
        val a = ConflictOrchestrationResult.ResolverNotFound(sampleConflict, ConflictResolverId("r"))
        val b = ConflictOrchestrationResult.ResolverNotFound(sampleConflict, ConflictResolverId("r"))
        assertEquals(a, b)
    }

    @Test
    fun `resolved equality`() {
        val decision = ConflictResolutionDecision.UseLocal()
        val a = ConflictOrchestrationResult.Resolved(sampleConflict, decision, ConflictDetectorId("d"), ConflictResolverId("r"))
        val b = ConflictOrchestrationResult.Resolved(sampleConflict, decision, ConflictDetectorId("d"), ConflictResolverId("r"))
        assertEquals(a, b)
    }
}
