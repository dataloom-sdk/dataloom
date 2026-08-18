package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictDetectorId
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Contract and real-orchestrator coverage for the reference detector catalog. */
class BuiltInConflictDetectorsTest {

    private val entityType = EntityType("note")
    private val entityId = EntityId("note-1")
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
    fun builtInsDoNotChangeTheApplicationDetectorSnapshot() {
        val registry = ConflictDetectorRegistry(emptyList())

        assertTrue(registry.detectors.isEmpty())
        assertEquals(
            ConflictType.CONCURRENT_CHANGE,
            detectedType(
                requireBuiltIn("dataloom.builtin.operation").detect(
                    request(ChangeOperation.UPDATE, ChangeOperation.UPDATE),
                ),
            ),
        )
    }

    @Test
    fun applicationRegistrationOverridesABuiltInWithTheSameId() {
        val overrideId = id("dataloom.builtin.operation")
        val override = object : ConflictDetector {
            override val id: ConflictDetectorId = overrideId

            override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
                ConflictDetectionResult.NoConflict
        }
        val registry = ConflictDetectorRegistry(listOf(override))

        assertSame(override, registry.lookup(overrideId))
        assertIs<ConflictDetectionResult.NoConflict>(
            requireNotNull(registry.lookup(overrideId)).detect(
                request(ChangeOperation.UPDATE, ChangeOperation.DELETE),
            ),
        )
    }

    @Test
    fun unknownDetectorIdStillReturnsNull() {
        assertNull(ConflictDetectorRegistry(emptyList()).lookup(id("dataloom.builtin.unknown")))
    }

    @Test
    fun identicalChangeIsNoConflictForEveryBuiltInDetector() {
        val event = event("same", ChangeOperation.UPDATE, version = "v1")
        val request = ConflictDetectionRequest(
            synchronizationRequest = synchronizationRequest,
            localChange = event,
            remoteChange = event,
        )

        for (detectorId in BUILT_IN_IDS) {
            assertIs<ConflictDetectionResult.NoConflict>(requireBuiltIn(detectorId).detect(request))
        }
    }

    @Test
    fun reusedEventIdWithDifferentFactsFailsClosedForEveryBuiltInDetector() {
        val local = event("same-id", ChangeOperation.UPDATE)
        val remote = event("same-id", ChangeOperation.DELETE)
        val request = detectionRequest(local, remote)

        for (detectorId in BUILT_IN_IDS) {
            val conflict = detected(requireBuiltIn(detectorId).detect(request))
            assertEquals(ConflictType.CUSTOM, conflict.type)
            assertEquals(
                "event-id-reused-with-different-facts",
                conflict.metadata[REASON_CODE_KEY],
            )
        }
    }

    @Test
    fun operationDetectorClassifiesCanonicalPairs() {
        val detector = requireBuiltIn("dataloom.builtin.operation")

        assertEquals(
            ConflictType.UPDATE_DELETE,
            detectedType(detector.detect(request(ChangeOperation.UPDATE, ChangeOperation.DELETE))),
        )
        assertEquals(
            ConflictType.DELETE_UPDATE,
            detectedType(detector.detect(request(ChangeOperation.DELETE, ChangeOperation.UPDATE))),
        )
        assertEquals(
            ConflictType.CREATE_COLLISION,
            detectedType(detector.detect(request(ChangeOperation.CREATE, ChangeOperation.CREATE))),
        )
        assertEquals(
            ConflictType.CONCURRENT_CHANGE,
            detectedType(detector.detect(request(ChangeOperation.MERGE, ChangeOperation.RESTORE))),
        )
        assertIs<ConflictDetectionResult.NoConflict>(
            detector.detect(request(ChangeOperation.DELETE, ChangeOperation.DELETE)),
        )
    }

    @Test
    fun versionDetectorDetectsMismatchAndAcceptsEqualVersions() {
        val detector = requireBuiltIn("dataloom.builtin.version")

        assertEquals(
            ConflictType.VERSION_MISMATCH,
            detectedType(
                detector.detect(
                    request(
                        localOperation = ChangeOperation.UPDATE,
                        remoteOperation = ChangeOperation.UPDATE,
                        localVersion = "v1",
                        remoteVersion = "v2",
                    ),
                ),
            ),
        )
        assertIs<ConflictDetectionResult.NoConflict>(
            detector.detect(
                request(
                    localOperation = ChangeOperation.UPDATE,
                    remoteOperation = ChangeOperation.UPDATE,
                    localVersion = "v2",
                    remoteVersion = "v2",
                ),
            ),
        )
    }

    @Test
    fun versionDetectorFailsClosedWhenVersionEvidenceIsMissing() {
        val conflict = detected(
            requireBuiltIn("dataloom.builtin.version").detect(
                request(
                    localOperation = ChangeOperation.UPDATE,
                    remoteOperation = ChangeOperation.UPDATE,
                    localVersion = "v1",
                    remoteVersion = null,
                ),
            ),
        )

        assertEquals(ConflictType.CUSTOM, conflict.type)
        assertEquals("version.evidence-missing", conflict.metadata[REASON_CODE_KEY])
    }

    @Test
    fun timestampDetectorFindsTwoChangesAfterTheSameBase() {
        val metadata = metadata(
            BASE_TIMESTAMP_KEY to "100",
            LOCAL_TIMESTAMP_KEY to "200",
            REMOTE_TIMESTAMP_KEY to "300",
        )

        val conflict = detected(
            requireBuiltIn("dataloom.builtin.timestamp").detect(
                request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = metadata),
            ),
        )

        assertEquals(ConflictType.CONCURRENT_CHANGE, conflict.type)
        assertEquals("timestamp.concurrent-change", conflict.metadata[REASON_CODE_KEY])
    }

    @Test
    fun timestampDetectorReturnsNoConflictWhenOnlyOneSideChangedAfterBase() {
        val metadata = metadata(
            BASE_TIMESTAMP_KEY to "100",
            LOCAL_TIMESTAMP_KEY to "100",
            REMOTE_TIMESTAMP_KEY to "300",
        )

        assertIs<ConflictDetectionResult.NoConflict>(
            requireBuiltIn("dataloom.builtin.timestamp").detect(
                request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = metadata),
            ),
        )
    }

    @Test
    fun timestampRequestEvidenceOverridesEventEvidence() {
        val localEventMetadata = metadata(EVENT_TIMESTAMP_KEY to "50")
        val remoteEventMetadata = metadata(EVENT_TIMESTAMP_KEY to "60")
        val requestMetadata = metadata(
            BASE_TIMESTAMP_KEY to "100",
            LOCAL_TIMESTAMP_KEY to "200",
            REMOTE_TIMESTAMP_KEY to "300",
        )

        val result = requireBuiltIn("dataloom.builtin.timestamp").detect(
            detectionRequest(
                local = event("local", ChangeOperation.UPDATE, metadata = localEventMetadata),
                remote = event("remote", ChangeOperation.UPDATE, metadata = remoteEventMetadata),
                metadata = requestMetadata,
            ),
        )

        assertEquals(ConflictType.CONCURRENT_CHANGE, detectedType(result))
    }

    @Test
    fun timestampDetectorUsesEventEvidenceWhenRequestSideValuesAreAbsent() {
        val requestMetadata = metadata(BASE_TIMESTAMP_KEY to "100")
        val localEventMetadata = metadata(EVENT_TIMESTAMP_KEY to "200")
        val remoteEventMetadata = metadata(EVENT_TIMESTAMP_KEY to "300")

        val result = requireBuiltIn("dataloom.builtin.timestamp").detect(
            detectionRequest(
                local = event("local", ChangeOperation.UPDATE, metadata = localEventMetadata),
                remote = event("remote", ChangeOperation.UPDATE, metadata = remoteEventMetadata),
                metadata = requestMetadata,
            ),
        )

        assertEquals(ConflictType.CONCURRENT_CHANGE, detectedType(result))
    }

    @Test
    fun timestampDetectorFailsClosedOnMissingOrMalformedEvidence() {
        val detector = requireBuiltIn("dataloom.builtin.timestamp")
        val missing = detected(
            detector.detect(
                request(
                    ChangeOperation.UPDATE,
                    ChangeOperation.UPDATE,
                    metadata = metadata(BASE_TIMESTAMP_KEY to "100", LOCAL_TIMESTAMP_KEY to "200"),
                ),
            ),
        )
        val malformed = detected(
            detector.detect(
                request(
                    ChangeOperation.UPDATE,
                    ChangeOperation.UPDATE,
                    metadata = metadata(
                        BASE_TIMESTAMP_KEY to "100",
                        LOCAL_TIMESTAMP_KEY to "not-a-number",
                        REMOTE_TIMESTAMP_KEY to "300",
                    ),
                ),
            ),
        )

        assertEquals("timestamp.remote-missing", missing.metadata[REASON_CODE_KEY])
        assertEquals("timestamp.local-malformed", malformed.metadata[REASON_CODE_KEY])
    }

    @Test
    fun etagDetectorFindsThreeWayDivergence() {
        val result = requireBuiltIn("dataloom.builtin.etag").detect(
            request(
                ChangeOperation.UPDATE,
                ChangeOperation.UPDATE,
                metadata = metadata(
                    BASE_ETAG_KEY to "base",
                    LOCAL_ETAG_KEY to "local",
                    REMOTE_ETAG_KEY to "remote",
                ),
            ),
        )

        assertEquals(ConflictType.VERSION_MISMATCH, detectedType(result))
    }

    @Test
    fun etagDetectorReturnsNoConflictForConvergenceOrOneSidedChange() {
        val detector = requireBuiltIn("dataloom.builtin.etag")
        val converged = metadata(
            BASE_ETAG_KEY to "base",
            LOCAL_ETAG_KEY to "next",
            REMOTE_ETAG_KEY to "next",
        )
        val oneSided = metadata(
            BASE_ETAG_KEY to "base",
            LOCAL_ETAG_KEY to "base",
            REMOTE_ETAG_KEY to "remote",
        )

        assertIs<ConflictDetectionResult.NoConflict>(
            detector.detect(request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = converged)),
        )
        assertIs<ConflictDetectionResult.NoConflict>(
            detector.detect(request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = oneSided)),
        )
    }

    @Test
    fun etagDetectorFallsBackToEventMetadataButRequestEvidenceWins() {
        val requestMetadata = metadata(
            BASE_ETAG_KEY to "base",
            LOCAL_ETAG_KEY to "request-local",
            REMOTE_ETAG_KEY to "request-remote",
        )
        val localEventMetadata = metadata(EVENT_ETAG_KEY to "base")
        val remoteEventMetadata = metadata(EVENT_ETAG_KEY to "base")

        val result = requireBuiltIn("dataloom.builtin.etag").detect(
            detectionRequest(
                local = event("local", ChangeOperation.UPDATE, metadata = localEventMetadata),
                remote = event("remote", ChangeOperation.UPDATE, metadata = remoteEventMetadata),
                metadata = requestMetadata,
            ),
        )

        assertEquals(ConflictType.VERSION_MISMATCH, detectedType(result))
    }

    @Test
    fun etagDetectorFailsClosedOnBlankEvidenceWithoutPersistingTheValue() {
        val secretShapedValue = "Bearer-do-not-copy"
        val conflict = detected(
            requireBuiltIn("dataloom.builtin.etag").detect(
                request(
                    ChangeOperation.UPDATE,
                    ChangeOperation.UPDATE,
                    metadata = metadata(
                        BASE_ETAG_KEY to secretShapedValue,
                        LOCAL_ETAG_KEY to "",
                        REMOTE_ETAG_KEY to "remote",
                    ),
                ),
            ),
        )

        assertEquals("etag.local-missing", conflict.metadata[REASON_CODE_KEY])
        assertTrue(secretShapedValue !in conflict.metadata.entries.values)
    }

    @Test
    fun vectorClockDetectorFindsIncomparableClocks() {
        val metadata = metadata(
            LOCAL_VECTOR_KEY to "client=2,server=1",
            REMOTE_VECTOR_KEY to "client=1,server=2",
        )

        assertEquals(
            ConflictType.CONCURRENT_CHANGE,
            detectedType(
                requireBuiltIn("dataloom.builtin.vector-clock").detect(
                    request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = metadata),
                ),
            ),
        )
    }

    @Test
    fun vectorClockDetectorAcceptsEqualOrDominatingClocks() {
        val detector = requireBuiltIn("dataloom.builtin.vector-clock")
        val equal = metadata(
            LOCAL_VECTOR_KEY to "client=2,server=1",
            REMOTE_VECTOR_KEY to "client=2,server=1",
        )
        val localDominates = metadata(
            LOCAL_VECTOR_KEY to "client=3,server=2",
            REMOTE_VECTOR_KEY to "client=2,server=1",
        )
        val remoteDominates = metadata(
            LOCAL_VECTOR_KEY to "client=1",
            REMOTE_VECTOR_KEY to "client=1,server=1",
        )

        assertIs<ConflictDetectionResult.NoConflict>(
            detector.detect(request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = equal)),
        )
        assertIs<ConflictDetectionResult.NoConflict>(
            detector.detect(
                request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = localDominates),
            ),
        )
        assertIs<ConflictDetectionResult.NoConflict>(
            detector.detect(
                request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = remoteDominates),
            ),
        )
    }

    @Test
    fun vectorClockDetectorUsesEventEvidenceWhenRequestValuesAreAbsent() {
        val localEvent = event(
            "local",
            ChangeOperation.UPDATE,
            metadata = metadata(EVENT_VECTOR_KEY to "client=2,server=1"),
        )
        val remoteEvent = event(
            "remote",
            ChangeOperation.UPDATE,
            metadata = metadata(EVENT_VECTOR_KEY to "client=1,server=2"),
        )

        val result = requireBuiltIn("dataloom.builtin.vector-clock").detect(
            detectionRequest(localEvent, remoteEvent),
        )

        assertEquals(ConflictType.CONCURRENT_CHANGE, detectedType(result))
    }

    @Test
    fun vectorClockDetectorFailsClosedForMalformedDuplicateOrNegativeCounters() {
        val detector = requireBuiltIn("dataloom.builtin.vector-clock")
        val malformed = listOf(
            "client",
            "client=1,client=2",
            "client=-1",
            "=1",
            "client=",
        )

        for (value in malformed) {
            val conflict = detected(
                detector.detect(
                    request(
                        ChangeOperation.UPDATE,
                        ChangeOperation.UPDATE,
                        metadata = metadata(
                            LOCAL_VECTOR_KEY to value,
                            REMOTE_VECTOR_KEY to "server=1",
                        ),
                    ),
                ),
            )
            assertEquals("vector-clock.local-malformed", conflict.metadata[REASON_CODE_KEY])
        }
    }

    @Test
    fun vectorClockDetectorRejectsMoreThanTheBoundedActorCount() {
        val tooManyActors = (0..64).joinToString(",") { index -> "node$index=1" }
        val conflict = detected(
            requireBuiltIn("dataloom.builtin.vector-clock").detect(
                request(
                    ChangeOperation.UPDATE,
                    ChangeOperation.UPDATE,
                    metadata = metadata(
                        LOCAL_VECTOR_KEY to tooManyActors,
                        REMOTE_VECTOR_KEY to "remote=1",
                    ),
                ),
            ),
        )

        assertEquals("vector-clock.local-malformed", conflict.metadata[REASON_CODE_KEY])
    }

    @Test
    fun applicationMetadataDetectorFindsOpaqueThreeWayDivergence() {
        val result = requireBuiltIn("dataloom.builtin.application-metadata").detect(
            request(
                ChangeOperation.UPDATE,
                ChangeOperation.UPDATE,
                metadata = metadata(
                    BASE_APPLICATION_KEY to "base",
                    LOCAL_APPLICATION_KEY to "local",
                    REMOTE_APPLICATION_KEY to "remote",
                ),
            ),
        )

        assertEquals(ConflictType.CUSTOM, detectedType(result))
    }

    @Test
    fun applicationMetadataDetectorAcceptsConvergenceAndFailsClosedOnMissingEvidence() {
        val detector = requireBuiltIn("dataloom.builtin.application-metadata")
        val converged = metadata(
            BASE_APPLICATION_KEY to "base",
            LOCAL_APPLICATION_KEY to "next",
            REMOTE_APPLICATION_KEY to "next",
        )
        val missing = metadata(
            BASE_APPLICATION_KEY to "base",
            LOCAL_APPLICATION_KEY to "next",
        )

        assertIs<ConflictDetectionResult.NoConflict>(
            detector.detect(request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = converged)),
        )
        assertEquals(
            "application-metadata.remote-missing",
            detected(
                detector.detect(
                    request(ChangeOperation.UPDATE, ChangeOperation.UPDATE, metadata = missing),
                ),
            ).metadata[REASON_CODE_KEY],
        )
    }

    @Test
    fun generatedConflictIdentityIsStablePerDetectorAndOrderedEventPair() {
        val request = request(ChangeOperation.UPDATE, ChangeOperation.DELETE)
        val operationDetector = requireBuiltIn("dataloom.builtin.operation")
        val versionDetector = requireBuiltIn("dataloom.builtin.version")

        val first = detected(operationDetector.detect(request))
        val repeated = detected(operationDetector.detect(request))
        val otherDetector = detected(versionDetector.detect(request))

        assertEquals(first.id, repeated.id)
        assertNotEquals(first.id, otherDetector.id)
        assertEquals("dataloom.builtin.operation", first.metadata[DETECTOR_ID_KEY])
        assertEquals(2, first.metadata.entries.size)
    }

    @Test
    fun orchestratorCanUseBuiltInDetectorAndBuiltInResolverWithoutRegistrations() = runTest {
        val orchestrator = SynchronizationConflictOrchestrator(
            detectorRegistry = ConflictDetectorRegistry(emptyList()),
            resolverRegistry = ConflictResolverRegistry(emptyList()),
        )
        val request = request(ChangeOperation.UPDATE, ChangeOperation.DELETE)
        val bindings = ConflictOrchestrationBindings(
            detectorId = id("dataloom.builtin.operation"),
            resolverId = ConflictResolverId("dataloom.builtin.server-wins"),
        )

        val result = orchestrator.detectAndResolve(
            ConflictOrchestrationRequest(request, bindings),
        )

        val resolved = assertIs<ConflictOrchestrationResult.Resolved>(result)
        assertEquals(ConflictType.UPDATE_DELETE, resolved.conflict.type)
        assertEquals(id("dataloom.builtin.operation"), resolved.detectorId)
        assertEquals(ConflictResolverId("dataloom.builtin.server-wins"), resolved.resolverId)
        assertIs<ConflictResolutionDecision.UseRemote>(resolved.decision)
    }

    private fun requireBuiltIn(value: String): ConflictDetector =
        requireNotNull(ConflictDetectorRegistry(emptyList()).lookup(id(value)))

    private fun id(value: String): ConflictDetectorId = ConflictDetectorId(value)

    private fun request(
        localOperation: ChangeOperation,
        remoteOperation: ChangeOperation,
        localVersion: String? = null,
        remoteVersion: String? = null,
        metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ): ConflictDetectionRequest = detectionRequest(
        local = event("local", localOperation, localVersion),
        remote = event("remote", remoteOperation, remoteVersion),
        metadata = metadata,
    )

    private fun detectionRequest(
        local: ChangeEvent,
        remote: ChangeEvent,
        metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ): ConflictDetectionRequest = ConflictDetectionRequest(
        synchronizationRequest = synchronizationRequest,
        localChange = local,
        remoteChange = remote,
        metadata = metadata,
    )

    private fun event(
        id: String,
        operation: ChangeOperation,
        version: String? = null,
        metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ): ChangeEvent = ChangeEvent(
        id = ChangeEventId(id),
        entity = EntityReference(
            type = entityType,
            id = entityId,
            version = version?.let(::EntityVersion),
        ),
        operation = operation,
        metadata = metadata,
    )

    private fun detected(result: ConflictDetectionResult) =
        assertIs<ConflictDetectionResult.ConflictDetected>(result).conflict

    private fun detectedType(result: ConflictDetectionResult): ConflictType = detected(result).type

    private fun metadata(vararg pairs: Pair<String, String>): DataLoomMetadata =
        DataLoomMetadata.of(mapOf(*pairs))

    private companion object {
        val BUILT_IN_IDS: List<String> = listOf(
            "dataloom.builtin.operation",
            "dataloom.builtin.version",
            "dataloom.builtin.timestamp",
            "dataloom.builtin.etag",
            "dataloom.builtin.vector-clock",
            "dataloom.builtin.application-metadata",
        )

        const val DETECTOR_ID_KEY: String = "dataloom.conflict.detector-id"
        const val REASON_CODE_KEY: String = "dataloom.conflict.reason-code"

        const val BASE_TIMESTAMP_KEY: String =
            "dataloom.conflict.base.updated-at-epoch-millis"
        const val LOCAL_TIMESTAMP_KEY: String =
            "dataloom.conflict.local.updated-at-epoch-millis"
        const val REMOTE_TIMESTAMP_KEY: String =
            "dataloom.conflict.remote.updated-at-epoch-millis"
        const val EVENT_TIMESTAMP_KEY: String =
            "dataloom.entity.updated-at-epoch-millis"

        const val BASE_ETAG_KEY: String = "dataloom.conflict.base.etag"
        const val LOCAL_ETAG_KEY: String = "dataloom.conflict.local.etag"
        const val REMOTE_ETAG_KEY: String = "dataloom.conflict.remote.etag"
        const val EVENT_ETAG_KEY: String = "dataloom.entity.etag"

        const val LOCAL_VECTOR_KEY: String = "dataloom.conflict.local.vector-clock"
        const val REMOTE_VECTOR_KEY: String = "dataloom.conflict.remote.vector-clock"
        const val EVENT_VECTOR_KEY: String = "dataloom.entity.vector-clock"

        const val BASE_APPLICATION_KEY: String = "dataloom.conflict.application.base"
        const val LOCAL_APPLICATION_KEY: String = "dataloom.conflict.application.local"
        const val REMOTE_APPLICATION_KEY: String = "dataloom.conflict.application.remote"
    }
}
