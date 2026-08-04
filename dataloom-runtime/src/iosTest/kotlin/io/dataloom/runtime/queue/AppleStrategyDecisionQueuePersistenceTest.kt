package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppleStrategyDecisionQueuePersistenceTest {

    @Test
    fun versionThreeSnapshotPreservesExactStrategyDecision() {
        val expected = decision()
        val original = entry(expected)
        val encoded = AppleQueueStateFileCodec.encodeSnapshot(
            AppleQueueSnapshot(entries = linkedMapOf(original.id.value to original)),
        )

        val decoded = AppleQueueStateFileCodec.decodeSnapshot(encoded)
            .entries.getValue(original.id.value)

        assertEquals(expected, decoded.strategyDecision)
    }

    @Test
    fun versionTwoSnapshotRemainsReadableWithoutInventingDecision() {
        val original = entry(null)
        val versionOne = AppleQueueStateFileCodec.encode(
            mapOf(original.id.value to original),
        )
        val entryLine = versionOne.lineSequence().drop(1).first { it.isNotEmpty() }
        val versionTwo = "DATALOOM_QUEUE_STATE\t2\nE\t$entryLine\n"

        val decoded = AppleQueueStateFileCodec.decodeSnapshot(versionTwo)
            .entries.getValue(original.id.value)

        assertNull(decoded.strategyDecision)
    }

    @Test
    fun partiallyPopulatedStrategyDecisionFailsClosed() {
        val presentEntry = entry(decision())
        val absentEntry = entry(null)
        val present = AppleQueueStateFileCodec.encodeSnapshot(
            AppleQueueSnapshot(entries = linkedMapOf(presentEntry.id.value to presentEntry)),
        ).split('\n').toMutableList()
        val absent = AppleQueueStateFileCodec.encodeSnapshot(
            AppleQueueSnapshot(entries = linkedMapOf(absentEntry.id.value to absentEntry)),
        ).split('\n')

        val presentFields = present[1].split('\t').toMutableList()
        val absentFields = absent[1].split('\t')
        presentFields[37] = absentFields[37]
        present[1] = presentFields.joinToString("\t")

        assertFailsWith<AppleQueueMalformedStateException> {
            AppleQueueStateFileCodec.decodeSnapshot(present.joinToString("\n"))
        }
    }

    private fun entry(decision: PersistedStrategyDecision?): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        strategyDecision = decision,
    )

    private fun decision(): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(9L),
        disposition = StrategyDisposition.DEFER,
    )
}
