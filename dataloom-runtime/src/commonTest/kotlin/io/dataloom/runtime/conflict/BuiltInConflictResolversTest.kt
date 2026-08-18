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
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Contract and orchestration coverage for the deterministic built-in catalog. */
class BuiltInConflictResolversTest {

    private val entity = EntityReference(EntityType("note"), EntityId("note-1"))
    private val localChange = ChangeEvent(ChangeEventId("local-1"), entity, ChangeOperation.UPDATE)
    private val remoteChange = ChangeEvent(ChangeEventId("remote-1"), entity, ChangeOperation.UPDATE)
    private val synchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("notes"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    )

    @Test
    fun builtInsDoNotChangeTheApplicationResolverSnapshot() {
        val registry = ConflictResolverRegistry(emptyList())

        assertTrue(registry.resolvers.isEmpty())
        assertIs<ConflictResolutionDecision.UseLocal>(
            requireNotNull(registry.lookup(id("dataloom.builtin.client-wins"))).resolve(request()),
        )
    }

    @Test
    fun clientWinsUsesLocalForEveryConflictType() {
        val resolver = requireBuiltIn("dataloom.builtin.client-wins")

        for (type in ConflictType.entries) {
            assertIs<ConflictResolutionDecision.UseLocal>(resolver.resolve(request(type = type)))
        }
    }

    @Test
    fun serverWinsUsesRemoteForEveryConflictType() {
        val resolver = requireBuiltIn("dataloom.builtin.server-wins")

        for (type in ConflictType.entries) {
            assertIs<ConflictResolutionDecision.UseRemote>(resolver.resolve(request(type = type)))
        }
    }

    @Test
    fun lastWriteWinsRemainsAvailableWithoutExplicitRegistration() {
        val registry = ConflictResolverRegistry(emptyList())
        val resolver = requireNotNull(registry.lookup(LastWriteWinsConflictResolver.DEFAULT_ID))

        assertIs<LastWriteWinsConflictResolver>(resolver)
        assertIs<ConflictResolutionDecision.UseRemote>(resolver.resolve(request()))
    }

    @Test
    fun manualPolicyDefersForFutureAuthorizedHandling() {
        val decision = requireBuiltIn("dataloom.builtin.manual").resolve(request())

        assertIs<ConflictResolutionDecision.Defer>(decision)
    }

    @Test
    fun rejectPolicyReturnsAStableNonRecoverableConflictError() {
        val decision = assertIs<ConflictResolutionDecision.Fail>(
            requireBuiltIn("dataloom.builtin.reject").resolve(request()),
        )

        assertEquals("DL-CONFLICT-REJECTED-BY-POLICY", decision.error.code.value)
        assertEquals(ErrorCategory.CONFLICT, decision.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, decision.error.recoverability)
        assertEquals(null, decision.error.cause)
    }

    @Test
    fun timestampPolicyUsesLocalWhenLocalEvidenceIsNewer() {
        val decision = timestampDecision(local = "200", remote = "100")

        assertIs<ConflictResolutionDecision.UseLocal>(decision)
    }

    @Test
    fun timestampPolicyUsesRemoteWhenRemoteEvidenceIsNewer() {
        val decision = timestampDecision(local = "100", remote = "200")

        assertIs<ConflictResolutionDecision.UseRemote>(decision)
    }

    @Test
    fun timestampPolicyUsesRemoteAsTheDeterministicEqualTimestampTiebreak() {
        val decision = timestampDecision(local = "200", remote = "200")

        assertIs<ConflictResolutionDecision.UseRemote>(decision)
    }

    @Test
    fun timestampPolicyDefersWhenEitherEvidenceValueIsMissing() {
        val resolver = requireBuiltIn("dataloom.builtin.timestamp")
        val onlyLocal = metadata(localTimestamp = "200", remoteTimestamp = null)
        val onlyRemote = metadata(localTimestamp = null, remoteTimestamp = "200")

        assertIs<ConflictResolutionDecision.Defer>(resolver.resolve(request(requestMetadata = onlyLocal)))
        assertIs<ConflictResolutionDecision.Defer>(resolver.resolve(request(requestMetadata = onlyRemote)))
    }

    @Test
    fun timestampPolicyDefersOnMalformedEvidenceInsteadOfInventingOrdering() {
        val decision = timestampDecision(local = "not-a-number", remote = "200")

        assertIs<ConflictResolutionDecision.Defer>(decision)
    }

    @Test
    fun requestTimestampEvidenceTakesPrecedenceOverConflictEvidence() {
        val resolver = requireBuiltIn("dataloom.builtin.timestamp")
        val requestMetadata = metadata(localTimestamp = "300", remoteTimestamp = "100")
        val conflictMetadata = metadata(localTimestamp = "100", remoteTimestamp = "300")

        val decision = resolver.resolve(
            request(
                requestMetadata = requestMetadata,
                conflictMetadata = conflictMetadata,
            ),
        )

        assertIs<ConflictResolutionDecision.UseLocal>(decision)
    }

    @Test
    fun malformedRequestEvidenceDoesNotFallBackToConflictingLowerPrecedenceEvidence() {
        val resolver = requireBuiltIn("dataloom.builtin.timestamp")
        val requestMetadata = metadata(localTimestamp = "bad", remoteTimestamp = "100")
        val conflictMetadata = metadata(localTimestamp = "300", remoteTimestamp = "100")

        val decision = resolver.resolve(
            request(
                requestMetadata = requestMetadata,
                conflictMetadata = conflictMetadata,
            ),
        )

        assertIs<ConflictResolutionDecision.Defer>(decision)
    }

    @Test
    fun applicationRegistrationOverridesABuiltInWithTheSameId() {
        val overrideId = id("dataloom.builtin.server-wins")
        val override = object : ConflictResolver {
            override val id: ConflictResolverId = overrideId

            override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision =
                ConflictResolutionDecision.UseLocal()
        }
        val registry = ConflictResolverRegistry(listOf(override))

        assertSame(override, registry.lookup(overrideId))
        assertIs<ConflictResolutionDecision.UseLocal>(requireNotNull(registry.lookup(overrideId)).resolve(request()))
    }

    @Test
    fun anUnknownIdentifierStillReturnsNull() {
        assertNull(ConflictResolverRegistry(emptyList()).lookup(id("dataloom.builtin.unknown")))
    }

    @Test
    fun orchestratorSelectsAndInvokesABuiltInWithoutApplicationRegistration() = runTest {
        val detectorId = ConflictDetectorId("detector-1")
        val conflict = conflict()
        val orchestrator = SynchronizationConflictOrchestrator(
            detectorRegistry = ConflictDetectorRegistry(
                listOf(FixedConflictDetector(detectorId, conflict)),
            ),
            resolverRegistry = ConflictResolverRegistry(emptyList()),
        )
        val bindings = ConflictOrchestrationBindings(
            detectorId = detectorId,
            resolverId = id("dataloom.builtin.server-wins"),
        )
        val detectionRequest = ConflictDetectionRequest(
            synchronizationRequest = synchronizationRequest,
            localChange = localChange,
            remoteChange = remoteChange,
        )

        val result = orchestrator.detectAndResolve(
            ConflictOrchestrationRequest(detectionRequest, bindings),
        )

        val resolved = assertIs<ConflictOrchestrationResult.Resolved>(result)
        assertEquals(id("dataloom.builtin.server-wins"), resolved.resolverId)
        assertIs<ConflictResolutionDecision.UseRemote>(resolved.decision)
    }

    private fun requireBuiltIn(value: String): ConflictResolver =
        requireNotNull(ConflictResolverRegistry(emptyList()).lookup(id(value)))

    private fun timestampDecision(local: String, remote: String): ConflictResolutionDecision =
        requireBuiltIn("dataloom.builtin.timestamp").resolve(
            request(requestMetadata = metadata(local, remote)),
        )

    private fun request(
        type: ConflictType = ConflictType.CONCURRENT_CHANGE,
        requestMetadata: DataLoomMetadata = DataLoomMetadata.Empty,
        conflictMetadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ): ConflictResolutionRequest = ConflictResolutionRequest(
        synchronizationRequest = synchronizationRequest,
        conflict = conflict(type, conflictMetadata),
        metadata = requestMetadata,
    )

    private fun conflict(
        type: ConflictType = ConflictType.CONCURRENT_CHANGE,
        metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ): SynchronizationConflict = SynchronizationConflict(
        id = ConflictId("conflict-${type.name.lowercase()}"),
        type = type,
        entity = entity,
        localChange = localChange,
        remoteChange = remoteChange,
        metadata = metadata,
    )

    private fun metadata(
        localTimestamp: String?,
        remoteTimestamp: String?,
    ): DataLoomMetadata {
        val entries = buildMap {
            if (localTimestamp != null) {
                put("dataloom.conflict.local.updated-at-epoch-millis", localTimestamp)
            }
            if (remoteTimestamp != null) {
                put("dataloom.conflict.remote.updated-at-epoch-millis", remoteTimestamp)
            }
        }
        return DataLoomMetadata.of(entries)
    }

    private fun id(value: String): ConflictResolverId = ConflictResolverId(value)

    private class FixedConflictDetector(
        override val id: ConflictDetectorId,
        private val conflict: SynchronizationConflict,
    ) : ConflictDetector {
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
            ConflictDetectionResult.ConflictDetected(conflict)
    }
}
