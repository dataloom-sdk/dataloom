package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.model.ChangeOperation

/** Resolves one deterministic reference detector by its exact documented ID. */
internal fun builtInConflictDetector(id: ConflictDetectorId): ConflictDetector? =
    BuiltInConflictDetectorCatalog.lookup(id)

private object BuiltInConflictDetectorCatalog {
    private val detectorsById: Map<ConflictDetectorId, ConflictDetector> = listOf(
        OperationConflictDetector,
        VersionMismatchConflictDetector,
        TimestampConflictDetector,
        EtagConflictDetector,
        VectorClockConflictDetector,
        ApplicationMetadataConflictDetector,
    ).associateBy(ConflictDetector::id)

    fun lookup(id: ConflictDetectorId): ConflictDetector? = detectorsById[id]
}

/**
 * Shared reference-detector discipline.
 *
 * Every detector is side-effect-free and derives a stable conflict identity
 * from its exact detector ID plus the ordered local/remote change-event IDs.
 * The identity preserves the full structural IDs rather than using a lossy
 * hash. Conflict metadata records bounded reason codes only; evidence values,
 * payloads, versions, ETags, vector clocks, and timestamps are not copied into
 * the conflict record.
 */
private abstract class BuiltInConflictDetector(
    final override val id: ConflictDetectorId,
) : ConflictDetector {

    protected fun commonPreflight(request: ConflictDetectionRequest): ConflictDetectionResult? {
        if (request.localChange == request.remoteChange) {
            return ConflictDetectionResult.NoConflict
        }
        if (request.localChange.id == request.remoteChange.id) {
            return conflict(
                request = request,
                type = ConflictType.CUSTOM,
                reasonCode = "event-id-reused-with-different-facts",
            )
        }
        return null
    }

    protected fun conflict(
        request: ConflictDetectionRequest,
        type: ConflictType,
        reasonCode: String,
    ): ConflictDetectionResult = ConflictDetectionResult.ConflictDetected(
        SynchronizationConflict(
            id = stableConflictId(request),
            type = type,
            entity = EntityReference(
                type = request.localChange.entity.type,
                id = request.localChange.entity.id,
            ),
            localChange = request.localChange,
            remoteChange = request.remoteChange,
            metadata = DataLoomMetadata.of(
                mapOf(
                    DETECTOR_ID_METADATA_KEY to id.value,
                    REASON_CODE_METADATA_KEY to reasonCode,
                ),
            ),
        ),
    )

    protected fun evidenceUnavailable(
        request: ConflictDetectionRequest,
        reasonCode: String,
    ): ConflictDetectionResult = conflict(
        request = request,
        type = ConflictType.CUSTOM,
        reasonCode = reasonCode,
    )

    protected fun requestOrEventEvidence(
        request: ConflictDetectionRequest,
        requestKey: String,
        event: ChangeEvent,
        eventKey: String,
    ): String? = request.metadata[requestKey] ?: event.metadata[eventKey]

    private fun stableConflictId(request: ConflictDetectionRequest): ConflictId {
        val local = request.localChange.id.value
        val remote = request.remoteChange.id.value
        return ConflictId(
            "dataloom.builtin.conflict/" +
                id.value + "/" +
                local.length + ":" + local + "/" +
                remote.length + ":" + remote,
        )
    }

    final override fun toString(): String = "BuiltInConflictDetector(id=${id.value})"

    protected companion object {
        const val DETECTOR_ID_METADATA_KEY: String = "dataloom.conflict.detector-id"
        const val REASON_CODE_METADATA_KEY: String = "dataloom.conflict.reason-code"
    }
}

/** Classifies structural operation-pair conflicts without inspecting payloads. */
private object OperationConflictDetector : BuiltInConflictDetector(
    ConflictDetectorId("dataloom.builtin.operation"),
) {
    override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
        commonPreflight(request)?.let { return it }

        val local = request.localChange.operation
        val remote = request.remoteChange.operation
        return when {
            local == ChangeOperation.DELETE && remote == ChangeOperation.DELETE ->
                ConflictDetectionResult.NoConflict
            local == ChangeOperation.UPDATE && remote == ChangeOperation.DELETE ->
                conflict(request, ConflictType.UPDATE_DELETE, "operation.update-delete")
            local == ChangeOperation.DELETE && remote == ChangeOperation.UPDATE ->
                conflict(request, ConflictType.DELETE_UPDATE, "operation.delete-update")
            local == ChangeOperation.CREATE && remote == ChangeOperation.CREATE ->
                conflict(request, ConflictType.CREATE_COLLISION, "operation.create-collision")
            else ->
                conflict(request, ConflictType.CONCURRENT_CHANGE, "operation.concurrent-change")
        }
    }
}

/** Detects unequal explicit entity versions; missing evidence fails closed. */
private object VersionMismatchConflictDetector : BuiltInConflictDetector(
    ConflictDetectorId("dataloom.builtin.version"),
) {
    override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
        commonPreflight(request)?.let { return it }

        val local = request.localChange.entity.version
            ?: return evidenceUnavailable(request, "version.evidence-missing")
        val remote = request.remoteChange.entity.version
            ?: return evidenceUnavailable(request, "version.evidence-missing")

        return if (local == remote) {
            ConflictDetectionResult.NoConflict
        } else {
            conflict(request, ConflictType.VERSION_MISMATCH, "version.mismatch")
        }
    }
}

/**
 * Detects concurrent changes when both explicit timestamps are newer than one
 * explicit base timestamp. Missing or malformed evidence fails closed.
 */
private object TimestampConflictDetector : BuiltInConflictDetector(
    ConflictDetectorId("dataloom.builtin.timestamp"),
) {
    override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
        commonPreflight(request)?.let { return it }

        val baseRaw = request.metadata[BASE_TIMESTAMP_KEY]
            ?: return evidenceUnavailable(request, "timestamp.base-missing")
        val localRaw = requestOrEventEvidence(
            request,
            LOCAL_TIMESTAMP_KEY,
            request.localChange,
            EVENT_TIMESTAMP_KEY,
        ) ?: return evidenceUnavailable(request, "timestamp.local-missing")
        val remoteRaw = requestOrEventEvidence(
            request,
            REMOTE_TIMESTAMP_KEY,
            request.remoteChange,
            EVENT_TIMESTAMP_KEY,
        ) ?: return evidenceUnavailable(request, "timestamp.remote-missing")

        val base = baseRaw.toLongOrNull()
            ?: return evidenceUnavailable(request, "timestamp.base-malformed")
        val local = localRaw.toLongOrNull()
            ?: return evidenceUnavailable(request, "timestamp.local-malformed")
        val remote = remoteRaw.toLongOrNull()
            ?: return evidenceUnavailable(request, "timestamp.remote-malformed")

        return if (local > base && remote > base) {
            conflict(request, ConflictType.CONCURRENT_CHANGE, "timestamp.concurrent-change")
        } else {
            ConflictDetectionResult.NoConflict
        }
    }

    private const val BASE_TIMESTAMP_KEY: String =
        "dataloom.conflict.base.updated-at-epoch-millis"
    private const val LOCAL_TIMESTAMP_KEY: String =
        "dataloom.conflict.local.updated-at-epoch-millis"
    private const val REMOTE_TIMESTAMP_KEY: String =
        "dataloom.conflict.remote.updated-at-epoch-millis"
    private const val EVENT_TIMESTAMP_KEY: String =
        "dataloom.entity.updated-at-epoch-millis"
}

/** Detects a three-way ETag divergence; missing or blank evidence fails closed. */
private object EtagConflictDetector : BuiltInConflictDetector(
    ConflictDetectorId("dataloom.builtin.etag"),
) {
    override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
        commonPreflight(request)?.let { return it }

        val base = request.metadata[BASE_ETAG_KEY].nonBlankOrNull()
            ?: return evidenceUnavailable(request, "etag.base-missing")
        val local = requestOrEventEvidence(
            request,
            LOCAL_ETAG_KEY,
            request.localChange,
            EVENT_ETAG_KEY,
        ).nonBlankOrNull() ?: return evidenceUnavailable(request, "etag.local-missing")
        val remote = requestOrEventEvidence(
            request,
            REMOTE_ETAG_KEY,
            request.remoteChange,
            EVENT_ETAG_KEY,
        ).nonBlankOrNull() ?: return evidenceUnavailable(request, "etag.remote-missing")

        return if (local != remote && local != base && remote != base) {
            conflict(request, ConflictType.VERSION_MISMATCH, "etag.diverged-from-base")
        } else {
            ConflictDetectionResult.NoConflict
        }
    }

    private fun String?.nonBlankOrNull(): String? = this?.takeIf(String::isNotBlank)

    private const val BASE_ETAG_KEY: String = "dataloom.conflict.base.etag"
    private const val LOCAL_ETAG_KEY: String = "dataloom.conflict.local.etag"
    private const val REMOTE_ETAG_KEY: String = "dataloom.conflict.remote.etag"
    private const val EVENT_ETAG_KEY: String = "dataloom.entity.etag"
}

/** Detects incomparable vector clocks using a strict bounded text encoding. */
private object VectorClockConflictDetector : BuiltInConflictDetector(
    ConflictDetectorId("dataloom.builtin.vector-clock"),
) {
    override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
        commonPreflight(request)?.let { return it }

        val localRaw = requestOrEventEvidence(
            request,
            LOCAL_VECTOR_CLOCK_KEY,
            request.localChange,
            EVENT_VECTOR_CLOCK_KEY,
        ) ?: return evidenceUnavailable(request, "vector-clock.local-missing")
        val remoteRaw = requestOrEventEvidence(
            request,
            REMOTE_VECTOR_CLOCK_KEY,
            request.remoteChange,
            EVENT_VECTOR_CLOCK_KEY,
        ) ?: return evidenceUnavailable(request, "vector-clock.remote-missing")

        val local = parseVectorClock(localRaw)
            ?: return evidenceUnavailable(request, "vector-clock.local-malformed")
        val remote = parseVectorClock(remoteRaw)
            ?: return evidenceUnavailable(request, "vector-clock.remote-malformed")

        var localAhead = false
        var remoteAhead = false
        for (actor in local.keys + remote.keys) {
            val localCounter = local[actor] ?: 0L
            val remoteCounter = remote[actor] ?: 0L
            if (localCounter > remoteCounter) localAhead = true
            if (remoteCounter > localCounter) remoteAhead = true
        }

        return if (localAhead && remoteAhead) {
            conflict(request, ConflictType.CONCURRENT_CHANGE, "vector-clock.concurrent")
        } else {
            ConflictDetectionResult.NoConflict
        }
    }

    private fun parseVectorClock(raw: String): Map<String, Long>? {
        if (raw.isBlank()) return null
        val parts = raw.split(',')
        if (parts.isEmpty() || parts.size > MAX_VECTOR_ACTORS) return null

        val result = LinkedHashMap<String, Long>(parts.size)
        for (part in parts) {
            if (part.count { it == '=' } != 1) return null
            val separator = part.indexOf('=')
            if (separator <= 0 || separator == part.lastIndex) return null
            val actor = part.substring(0, separator).trim()
            val counterText = part.substring(separator + 1).trim()
            if (actor.isBlank() || actor.length > MAX_ACTOR_LENGTH) return null
            if (result.containsKey(actor)) return null
            val counter = counterText.toLongOrNull() ?: return null
            if (counter < 0L) return null
            result[actor] = counter
        }
        return result
    }

    private const val LOCAL_VECTOR_CLOCK_KEY: String =
        "dataloom.conflict.local.vector-clock"
    private const val REMOTE_VECTOR_CLOCK_KEY: String =
        "dataloom.conflict.remote.vector-clock"
    private const val EVENT_VECTOR_CLOCK_KEY: String =
        "dataloom.entity.vector-clock"
    private const val MAX_VECTOR_ACTORS: Int = 64
    private const val MAX_ACTOR_LENGTH: Int = 64
}

/**
 * Detects three-way divergence of an application-owned opaque revision marker.
 * Marker values are compared only for equality and are never persisted in the
 * generated conflict metadata.
 */
private object ApplicationMetadataConflictDetector : BuiltInConflictDetector(
    ConflictDetectorId("dataloom.builtin.application-metadata"),
) {
    override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
        commonPreflight(request)?.let { return it }

        val base = request.metadata[BASE_MARKER_KEY].nonBlankOrNull()
            ?: return evidenceUnavailable(request, "application-metadata.base-missing")
        val local = request.metadata[LOCAL_MARKER_KEY].nonBlankOrNull()
            ?: return evidenceUnavailable(request, "application-metadata.local-missing")
        val remote = request.metadata[REMOTE_MARKER_KEY].nonBlankOrNull()
            ?: return evidenceUnavailable(request, "application-metadata.remote-missing")

        return if (local != remote && local != base && remote != base) {
            conflict(request, ConflictType.CUSTOM, "application-metadata.diverged-from-base")
        } else {
            ConflictDetectionResult.NoConflict
        }
    }

    private fun String?.nonBlankOrNull(): String? = this?.takeIf(String::isNotBlank)

    private const val BASE_MARKER_KEY: String = "dataloom.conflict.application.base"
    private const val LOCAL_MARKER_KEY: String = "dataloom.conflict.application.local"
    private const val REMOTE_MARKER_KEY: String = "dataloom.conflict.application.remote"
}
