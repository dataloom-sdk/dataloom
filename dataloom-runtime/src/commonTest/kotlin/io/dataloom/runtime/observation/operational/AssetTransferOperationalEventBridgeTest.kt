package io.dataloom.runtime.observation.operational

import io.dataloom.api.asset.AssetChunkLayout
import io.dataloom.api.asset.AssetChunkDescriptor
import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetTransferDirection
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferOperation
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferPhase
import io.dataloom.assets.AssetTransferSession
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-mapping tests for [AssetTransferOperationalEventBridge]: proves it maps
 * every [AssetTransferOutcome] variant to a sane
 * [io.dataloom.api.operational.OperationalEventEnvelope], and that field
 * classification never leaks a raw sensitive value.
 */
class AssetTransferOperationalEventBridgeTest {

    private val witnessedAt = DataLoomInstant(9_000_000L)
    private val REDACTED = "[REDACTED]"

    private fun manifest(assetId: String = "asset-1", chunkCount: Int = 2): AssetManifest {
        val chunks = (0 until chunkCount).map { index ->
            AssetChunkDescriptor(
                index = index,
                offsetBytes = index * 1_024L,
                lengthBytes = 1_024L,
                checksum = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32)),
            )
        }
        return AssetManifest(
            assetId = AssetId(assetId),
            version = 1L,
            sizeBytes = chunkCount * 1_024L,
            mediaType = AssetMediaType("application/octet-stream"),
            checksum = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32)),
            chunkLayout = AssetChunkLayout(chunks),
        )
    }

    private fun session(
        sessionId: String = "session-should-be-masked",
        direction: AssetTransferDirection = AssetTransferDirection.UPLOAD,
        phase: AssetTransferPhase = AssetTransferPhase.COMPLETED,
        committedChunks: Set<Int> = setOf(0, 1),
        failure: AssetErrorKind? = null,
        revision: Long = 3L,
    ) = AssetTransferSession(
        sessionId = AssetTransferSessionId(sessionId),
        direction = direction,
        manifest = manifest(),
        phase = phase,
        committedChunks = committedChunks,
        failure = failure,
        revision = revision,
    )

    private val sampleError: DataLoomError = AssetTransferError(AssetErrorKind.PROVIDER_UNAVAILABLE, "raw-message-should-be-removed")

    // -------------------------------------------------------------------------
    // Identity/routing
    // -------------------------------------------------------------------------

    @Test
    fun toEnvelope_reusesSessionId_asCorrelationId_neverInventsOne() {
        val envelope = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("my-session"),
            AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(session(sessionId = "my-session")),
            witnessedAt,
        )
        assertEquals(CorrelationId("my-session"), envelope.correlationId)
        assertNull(envelope.traceId)
        assertNull(envelope.tenantId)
        assertNull(envelope.workflowId)
    }

    @Test
    fun toEnvelope_category_isLifecycle() {
        val envelope = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(session()), witnessedAt,
        )
        assertEquals(OperationalEventCategory.LIFECYCLE, envelope.category)
    }

    @Test
    fun toEnvelope_occurredAt_isAlwaysWitnessedAt() {
        val envelope = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(session()), witnessedAt,
        )
        assertEquals(witnessedAt, envelope.occurredAt)
    }

    @Test
    fun toEnvelope_typeReflectsOperationAndOutcome() {
        val s = session()
        assertEquals(
            "dataloom.asset.transfer.upload.completed",
            AssetTransferOperationalEventBridge.toEnvelope(
                AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD, AssetTransferOutcome.Completed(s), witnessedAt,
            ).type.value,
        )
        assertEquals(
            "dataloom.asset.transfer.download.interrupted",
            AssetTransferOperationalEventBridge.toEnvelope(
                AssetTransferSessionId("s"), AssetTransferOperation.DOWNLOAD,
                AssetTransferOutcome.Interrupted(s, sampleError), witnessedAt,
            ).type.value,
        )
        assertEquals(
            "dataloom.asset.transfer.upload.failed",
            AssetTransferOperationalEventBridge.toEnvelope(
                AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD,
                AssetTransferOutcome.Failed(session(phase = AssetTransferPhase.FAILED, failure = AssetErrorKind.QUOTA_EXCEEDED)),
                witnessedAt,
            ).type.value,
        )
        assertEquals(
            "dataloom.asset.transfer.cancel.cancelled",
            AssetTransferOperationalEventBridge.toEnvelope(
                AssetTransferSessionId("s"), AssetTransferOperation.CANCEL,
                AssetTransferOutcome.Cancelled(session(phase = AssetTransferPhase.CANCELLED)), witnessedAt,
            ).type.value,
        )
        assertEquals(
            "dataloom.asset.transfer.upload.not_started",
            AssetTransferOperationalEventBridge.toEnvelope(
                AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD, AssetTransferOutcome.NotStarted(sampleError), witnessedAt,
            ).type.value,
        )
        assertEquals(
            "dataloom.asset.transfer.upload.session_store_failure",
            AssetTransferOperationalEventBridge.toEnvelope(
                AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD,
                AssetTransferOutcome.SessionStoreFailure(sampleError), witnessedAt,
            ).type.value,
        )
    }

    @Test
    fun toEnvelope_isPureAndDeterministic() {
        val outcome = AssetTransferOutcome.Completed(session())
        val first = AssetTransferOperationalEventBridge.toEnvelope(AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD, outcome, witnessedAt)
        val second = AssetTransferOperationalEventBridge.toEnvelope(AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD, outcome, witnessedAt)
        assertEquals(first, second)
    }

    @Test
    fun toEnvelope_differentRevision_producesDifferentEnvelopeId_forSameSession() {
        val first = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("repeat"), AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Interrupted(session(sessionId = "repeat", phase = AssetTransferPhase.TRANSFERRING, revision = 2L), sampleError),
            witnessedAt,
        )
        val second = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("repeat"), AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(session(sessionId = "repeat", revision = 5L)),
            witnessedAt,
        )
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun toEnvelope_sameRevisionAndOutcome_producesTheSameEnvelopeId_idempotent() {
        val one = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("same"), AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(session(sessionId = "same", revision = 7L)), witnessedAt,
        )
        val two = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("same"), AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(session(sessionId = "same", revision = 7L)), witnessedAt,
        )
        assertEquals(one.id, two.id)
    }

    @Test
    fun toEnvelope_sanitizesDisallowedCharacters_inSessionId() {
        val envelope = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("session id!#weird"), AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(session(sessionId = "session id!#weird")), witnessedAt,
        )
        assertTrue(envelope.id.value.none { it == ' ' || it == '!' || it == '#' })
        assertTrue(envelope.id.value.length <= 128)
    }

    @Test
    fun toEnvelope_noSessionOutcomes_stillProduceDistinctIdsAcrossDifferentWitnessedAt() {
        val first = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD, AssetTransferOutcome.NotStarted(sampleError), DataLoomInstant(1L),
        )
        val second = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD, AssetTransferOutcome.NotStarted(sampleError), DataLoomInstant(2L),
        )
        assertNotEquals(first.id, second.id)
    }

    // -------------------------------------------------------------------------
    // Classification
    // -------------------------------------------------------------------------

    @Test
    fun toEnvelope_removesErrorMessage_keepsErrorCodeCategorySeverityRecoverability() {
        val envelope = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Interrupted(session(phase = AssetTransferPhase.TRANSFERRING), sampleError), witnessedAt,
        )
        assertNull(envelope.attributes["transfer.error.message"])
        assertEquals("dataloom.assets.provider_unavailable", envelope.attributes["transfer.error.code"])
        assertEquals("NETWORK", envelope.attributes["transfer.error.category"])
        assertEquals("ERROR", envelope.attributes["transfer.error.severity"])
        assertEquals("RECOVERABLE", envelope.attributes["transfer.error.recoverability"])
    }

    @Test
    fun toEnvelope_completedAndCancelled_haveNoErrorAttributes() {
        val completed = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD, AssetTransferOutcome.Completed(session()), witnessedAt,
        )
        assertNull(completed.attributes["transfer.error.code"])
        val cancelled = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.CANCEL,
            AssetTransferOutcome.Cancelled(session(phase = AssetTransferPhase.CANCELLED)), witnessedAt,
        )
        assertNull(cancelled.attributes["transfer.error.code"])
    }

    @Test
    fun toEnvelope_maskAssetId_neverKeepsRawValue() {
        val envelope = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD,
            AssetTransferOutcome.Completed(session()), witnessedAt,
        )
        val value = envelope.attributes["transfer.assetId"]
        assertTrue(value != null && value != "asset-1", "Expected redaction of 'asset-1', got $value")
    }

    @Test
    fun toEnvelope_keepsPublicClosedVocabularyAndCounters() {
        val envelope = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.DOWNLOAD,
            AssetTransferOutcome.Completed(session(direction = AssetTransferDirection.DOWNLOAD, committedChunks = setOf(0, 1))),
            witnessedAt,
        )
        assertEquals("DOWNLOAD", envelope.attributes["transfer.operation"])
        assertEquals("COMPLETED", envelope.attributes["transfer.outcome"])
        assertEquals("DOWNLOAD", envelope.attributes["transfer.direction"])
        assertEquals("COMPLETED", envelope.attributes["transfer.phase"])
        assertEquals("2", envelope.attributes["transfer.chunkCount"])
        assertEquals("2", envelope.attributes["transfer.committedChunkCount"])
        assertEquals("application/octet-stream", envelope.attributes["transfer.mediaType"])
    }

    @Test
    fun toEnvelope_noSessionOutcomes_haveNoSessionAttributes_butStillHaveOperationAndOutcome() {
        val envelope = AssetTransferOperationalEventBridge.toEnvelope(
            AssetTransferSessionId("s"), AssetTransferOperation.UPLOAD, AssetTransferOutcome.NotStarted(sampleError), witnessedAt,
        )
        assertEquals("UPLOAD", envelope.attributes["transfer.operation"])
        assertEquals("NOT_STARTED", envelope.attributes["transfer.outcome"])
        assertNull(envelope.attributes["transfer.direction"])
        assertNull(envelope.attributes["transfer.phase"])
        assertEquals("dataloom.assets.provider_unavailable", envelope.attributes["transfer.error.code"])
    }
}
