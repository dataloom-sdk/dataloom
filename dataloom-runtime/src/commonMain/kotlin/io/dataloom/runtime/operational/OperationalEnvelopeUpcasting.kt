package io.dataloom.runtime.operational

import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalSchemaVersion
import kotlin.coroutines.cancellation.CancellationException

/** One explicit, deterministic schema transition for one operational event type. */
public interface OperationalEnvelopeUpcaster {
    public val eventType: OperationalEventType
    public val fromSchemaVersion: OperationalSchemaVersion
    public val toSchemaVersion: OperationalSchemaVersion

    /** Returns one envelope at exactly [toSchemaVersion]. */
    public fun upcast(envelope: OperationalEventEnvelope): OperationalEventEnvelope
}

/** Stable failure classification for an operational-envelope upcast request. */
public enum class OperationalEnvelopeUpcastFailure {
    TARGET_OLDER_THAN_RECORD,
    NO_UPCAST_PATH,
    UPCASTER_FAILED,
    UPCASTER_CONTRACT_VIOLATION,
    STEP_LIMIT_EXCEEDED,
}

/** Structured result of applying a registered upcast chain. */
public sealed interface OperationalEnvelopeUpcastResult {
    /** The input already has the requested schema version. */
    public data class NotRequired(
        public val envelope: OperationalEventEnvelope,
    ) : OperationalEnvelopeUpcastResult

    /** The requested version was reached through one or more transitions. */
    public data class Upcasted(
        public val envelope: OperationalEventEnvelope,
        public val appliedStepCount: Int,
    ) : OperationalEnvelopeUpcastResult {
        init {
            require(appliedStepCount > 0) {
                "An upcast result must contain at least one applied step."
            }
        }
    }

    /** The requested version could not be reached safely. */
    public data class Rejected(
        public val failure: OperationalEnvelopeUpcastFailure,
        public val lastSchemaVersion: OperationalSchemaVersion,
        public val appliedStepCount: Int,
    ) : OperationalEnvelopeUpcastResult {
        init {
            require(appliedStepCount >= 0) {
                "Rejected upcast step count must not be negative."
            }
        }
    }
}

/**
 * Immutable registry that applies explicit operational-event schema transitions.
 *
 * Upcasters are selected by exact event type and exact source version. A step
 * may not change envelope identity, routing, correlation, source, occurrence
 * time, or category. Only schema-governed payload descriptors and already
 * redacted attributes may evolve.
 */
public class OperationalEnvelopeUpcasterRegistry(
    upcasters: Collection<OperationalEnvelopeUpcaster>,
) {
    private val transitions: Map<OperationalUpcastKey, OperationalEnvelopeUpcaster>

    init {
        val snapshot: List<OperationalEnvelopeUpcaster> = upcasters.toList()
        val registered: MutableMap<OperationalUpcastKey, OperationalEnvelopeUpcaster> =
            linkedMapOf()
        snapshot.forEach { upcaster: OperationalEnvelopeUpcaster ->
            require(upcaster.toSchemaVersion.value > upcaster.fromSchemaVersion.value) {
                "Operational upcaster versions must advance monotonically."
            }
            val key = OperationalUpcastKey(
                eventType = upcaster.eventType,
                fromSchemaVersion = upcaster.fromSchemaVersion,
            )
            require(registered.put(key, upcaster) == null) {
                "Duplicate operational upcaster transition."
            }
        }
        transitions = registered.toMap()
    }

    public val size: Int
        get() = transitions.size

    /** Applies a bounded chain to [targetSchemaVersion] or returns a safe rejection. */
    public fun upcast(
        envelope: OperationalEventEnvelope,
        targetSchemaVersion: OperationalSchemaVersion,
    ): OperationalEnvelopeUpcastResult {
        if (targetSchemaVersion.value < envelope.schemaVersion.value) {
            return OperationalEnvelopeUpcastResult.Rejected(
                failure = OperationalEnvelopeUpcastFailure.TARGET_OLDER_THAN_RECORD,
                lastSchemaVersion = envelope.schemaVersion,
                appliedStepCount = 0,
            )
        }
        if (targetSchemaVersion == envelope.schemaVersion) {
            return OperationalEnvelopeUpcastResult.NotRequired(envelope)
        }

        var current: OperationalEventEnvelope = envelope
        var appliedSteps: Int = 0
        while (current.schemaVersion.value < targetSchemaVersion.value) {
            if (appliedSteps >= MAX_UPCAST_STEPS) {
                return rejected(
                    OperationalEnvelopeUpcastFailure.STEP_LIMIT_EXCEEDED,
                    current,
                    appliedSteps,
                )
            }
            val upcaster: OperationalEnvelopeUpcaster = transitions[
                OperationalUpcastKey(current.type, current.schemaVersion),
            ] ?: return rejected(
                OperationalEnvelopeUpcastFailure.NO_UPCAST_PATH,
                current,
                appliedSteps,
            )
            if (upcaster.toSchemaVersion.value > targetSchemaVersion.value) {
                return rejected(
                    OperationalEnvelopeUpcastFailure.NO_UPCAST_PATH,
                    current,
                    appliedSteps,
                )
            }

            val next: OperationalEventEnvelope = try {
                upcaster.upcast(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return rejected(
                    OperationalEnvelopeUpcastFailure.UPCASTER_FAILED,
                    current,
                    appliedSteps,
                )
            }
            if (!upcasterOutputIsValid(current, next, upcaster)) {
                return rejected(
                    OperationalEnvelopeUpcastFailure.UPCASTER_CONTRACT_VIOLATION,
                    current,
                    appliedSteps,
                )
            }
            current = next
            appliedSteps += 1
        }

        return OperationalEnvelopeUpcastResult.Upcasted(
            envelope = current,
            appliedStepCount = appliedSteps,
        )
    }

    override fun toString(): String =
        "OperationalEnvelopeUpcasterRegistry(transitionCount=${transitions.size})"

    private fun rejected(
        failure: OperationalEnvelopeUpcastFailure,
        envelope: OperationalEventEnvelope,
        appliedSteps: Int,
    ): OperationalEnvelopeUpcastResult.Rejected = OperationalEnvelopeUpcastResult.Rejected(
        failure = failure,
        lastSchemaVersion = envelope.schemaVersion,
        appliedStepCount = appliedSteps,
    )

    private fun upcasterOutputIsValid(
        previous: OperationalEventEnvelope,
        next: OperationalEventEnvelope,
        upcaster: OperationalEnvelopeUpcaster,
    ): Boolean =
        next.schemaVersion == upcaster.toSchemaVersion &&
            next.id == previous.id &&
            next.type == previous.type &&
            next.source == previous.source &&
            next.category == previous.category &&
            next.occurredAt == previous.occurredAt &&
            next.correlationId == previous.correlationId &&
            next.causationId == previous.causationId &&
            next.traceId == previous.traceId &&
            next.tenantId == previous.tenantId &&
            next.workflowId == previous.workflowId

    private companion object {
        const val MAX_UPCAST_STEPS: Int = 32
    }
}

private data class OperationalUpcastKey(
    val eventType: OperationalEventType,
    val fromSchemaVersion: OperationalSchemaVersion,
)
