package io.dataloom.runtime.operational

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.security.DataClassification
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OperationalEnvelopeUpcastingTest {
    @Test
    fun registryAppliesDeterministicMultiStepChain() {
        val registry = OperationalEnvelopeUpcasterRegistry(
            listOf(
                schemaUpcaster(from = 1, to = 2),
                schemaUpcaster(from = 2, to = 3),
            ),
        )

        val result = assertIs<OperationalEnvelopeUpcastResult.Upcasted>(
            registry.upcast(envelope(version = 1), OperationalSchemaVersion(3)),
        )

        assertEquals(OperationalSchemaVersion(3), result.envelope.schemaVersion)
        assertEquals(2, result.appliedStepCount)
        assertEquals(OperationalEventId("event-001"), result.envelope.id)
    }

    @Test
    fun targetAtCurrentVersionReturnsOriginalInstance() {
        val original = envelope(version = 2)
        val result = assertIs<OperationalEnvelopeUpcastResult.NotRequired>(
            OperationalEnvelopeUpcasterRegistry(emptyList()).upcast(
                original,
                OperationalSchemaVersion(2),
            ),
        )

        assertEquals(original, result.envelope)
    }

    @Test
    fun missingAndOvershootingPathsAreRejected() {
        val emptyRegistry = OperationalEnvelopeUpcasterRegistry(emptyList())
        val missing = assertIs<OperationalEnvelopeUpcastResult.Rejected>(
            emptyRegistry.upcast(envelope(version = 1), OperationalSchemaVersion(2)),
        )
        assertEquals(OperationalEnvelopeUpcastFailure.NO_UPCAST_PATH, missing.failure)

        val overshootingRegistry = OperationalEnvelopeUpcasterRegistry(
            listOf(schemaUpcaster(from = 1, to = 3)),
        )
        val overshooting = assertIs<OperationalEnvelopeUpcastResult.Rejected>(
            overshootingRegistry.upcast(envelope(version = 1), OperationalSchemaVersion(2)),
        )
        assertEquals(OperationalEnvelopeUpcastFailure.NO_UPCAST_PATH, overshooting.failure)
    }

    @Test
    fun olderTargetIsRejectedWithoutCallingAnUpcaster() {
        var callCount = 0
        val registry = OperationalEnvelopeUpcasterRegistry(
            listOf(
                object : OperationalEnvelopeUpcaster {
                    override val eventType = EVENT_TYPE
                    override val fromSchemaVersion = OperationalSchemaVersion(1)
                    override val toSchemaVersion = OperationalSchemaVersion(2)

                    override fun upcast(
                        envelope: OperationalEventEnvelope,
                    ): OperationalEventEnvelope {
                        callCount += 1
                        return envelope.copy(schemaVersion = toSchemaVersion)
                    }
                },
            ),
        )

        val result = assertIs<OperationalEnvelopeUpcastResult.Rejected>(
            registry.upcast(envelope(version = 2), OperationalSchemaVersion(1)),
        )

        assertEquals(OperationalEnvelopeUpcastFailure.TARGET_OLDER_THAN_RECORD, result.failure)
        assertEquals(0, callCount)
    }

    @Test
    fun duplicateAndNonAdvancingTransitionsAreRejectedAtConstruction() {
        val transition = schemaUpcaster(from = 1, to = 2)
        assertFailsWith<IllegalArgumentException> {
            OperationalEnvelopeUpcasterRegistry(listOf(transition, transition))
        }
        assertFailsWith<IllegalArgumentException> {
            OperationalEnvelopeUpcasterRegistry(listOf(schemaUpcaster(from = 2, to = 2)))
        }
    }

    @Test
    fun outputThatChangesStableIdentityIsRejected() {
        val upcaster = object : OperationalEnvelopeUpcaster {
            override val eventType = EVENT_TYPE
            override val fromSchemaVersion = OperationalSchemaVersion(1)
            override val toSchemaVersion = OperationalSchemaVersion(2)

            override fun upcast(
                envelope: OperationalEventEnvelope,
            ): OperationalEventEnvelope = envelope.copy(
                source = OperationalEventSource("different.source"),
                schemaVersion = toSchemaVersion,
            )
        }

        val result = assertIs<OperationalEnvelopeUpcastResult.Rejected>(
            OperationalEnvelopeUpcasterRegistry(listOf(upcaster)).upcast(
                envelope(version = 1),
                OperationalSchemaVersion(2),
            ),
        )

        assertEquals(
            OperationalEnvelopeUpcastFailure.UPCASTER_CONTRACT_VIOLATION,
            result.failure,
        )
    }

    @Test
    fun ordinaryUpcasterFailureIsIsolatedButCancellationPropagates() {
        val failing = throwingUpcaster(IllegalStateException("sensitive failure"))
        val failed = assertIs<OperationalEnvelopeUpcastResult.Rejected>(
            OperationalEnvelopeUpcasterRegistry(listOf(failing)).upcast(
                envelope(version = 1),
                OperationalSchemaVersion(2),
            ),
        )
        assertEquals(OperationalEnvelopeUpcastFailure.UPCASTER_FAILED, failed.failure)

        val cancelled = throwingUpcaster(CancellationException("cancelled"))
        assertFailsWith<CancellationException> {
            OperationalEnvelopeUpcasterRegistry(listOf(cancelled)).upcast(
                envelope(version = 1),
                OperationalSchemaVersion(2),
            )
        }
    }

    private fun schemaUpcaster(from: Int, to: Int): OperationalEnvelopeUpcaster =
        object : OperationalEnvelopeUpcaster {
            override val eventType = EVENT_TYPE
            override val fromSchemaVersion = OperationalSchemaVersion(from)
            override val toSchemaVersion = OperationalSchemaVersion(to)

            override fun upcast(
                envelope: OperationalEventEnvelope,
            ): OperationalEventEnvelope = envelope.copy(schemaVersion = toSchemaVersion)
        }

    private fun throwingUpcaster(exception: Exception): OperationalEnvelopeUpcaster =
        object : OperationalEnvelopeUpcaster {
            override val eventType = EVENT_TYPE
            override val fromSchemaVersion = OperationalSchemaVersion(1)
            override val toSchemaVersion = OperationalSchemaVersion(2)

            override fun upcast(envelope: OperationalEventEnvelope): OperationalEventEnvelope =
                throw exception
        }

    private fun envelope(version: Int): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId("event-001"),
        type = EVENT_TYPE,
        source = OperationalEventSource("dataloom.runtime"),
        category = OperationalEventCategory.LIFECYCLE,
        schemaVersion = OperationalSchemaVersion(version),
        occurredAt = DataLoomInstant(1_000L),
        correlationId = CorrelationId("correlation-001"),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.lifecycle.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )

    private companion object {
        val EVENT_TYPE: OperationalEventType =
            OperationalEventType("dataloom.lifecycle.started")
    }
}
