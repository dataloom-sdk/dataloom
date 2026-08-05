package io.dataloom.api.strategy

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class StrategyOfflineFirstAdmissionTest {

    @Test
    fun `request requires durable offline-first admission plan`() {
        val plan = plan(
            disposition = StrategyDisposition.DEFER,
            strategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        )

        val request = StrategyOfflineFirstAdmissionRequest(
            request = synchronizationRequest(),
            decisionId = StrategyDecisionId("decision-1"),
            plan = plan,
            trigger = StrategyExecutionTrigger.DIRECT,
            queueEntryId = QueueEntryId("queue-1"),
            idempotencyKey = "intent-1",
        )

        assertEquals("intent-1", request.idempotencyKey)
        assertEquals(StrategyDisposition.DEFER, request.plan.disposition)
        assertFalse(request.toString().contains("intent-1"))
    }

    @Test
    fun `request rejects non offline-first and incomplete plans`() {
        assertFailsWith<IllegalArgumentException> {
            StrategyOfflineFirstAdmissionRequest(
                request = synchronizationRequest(),
                decisionId = StrategyDecisionId("decision-1"),
                plan = plan(
                    disposition = StrategyDisposition.EXECUTE,
                    strategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                ),
                trigger = StrategyExecutionTrigger.DIRECT,
                queueEntryId = QueueEntryId("queue-1"),
                idempotencyKey = "intent-1",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            StrategyOfflineFirstAdmissionRequest(
                request = synchronizationRequest(),
                decisionId = StrategyDecisionId("decision-1"),
                plan = plan(
                    disposition = StrategyDisposition.DEFER,
                    strategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
                    includeAtomicCapability = false,
                ),
                trigger = StrategyExecutionTrigger.DIRECT,
                queueEntryId = QueueEntryId("queue-1"),
                idempotencyKey = "intent-1",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            StrategyOfflineFirstAdmissionRequest(
                request = synchronizationRequest(),
                decisionId = StrategyDecisionId("decision-1"),
                plan = plan(
                    disposition = StrategyDisposition.DEFER,
                    strategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
                    includeAdmission = false,
                ),
                trigger = StrategyExecutionTrigger.DIRECT,
                queueEntryId = QueueEntryId("queue-1"),
                idempotencyKey = "intent-1",
            )
        }
    }

    @Test
    fun `accepted results preserve idempotency identity`() {
        val accepted = StrategyOfflineFirstAdmissionResult.Accepted(
            queueEntryId = QueueEntryId("queue-1"),
            idempotencyKey = "intent-1",
        )
        val duplicate = StrategyOfflineFirstAdmissionResult.AlreadyAccepted(
            queueEntryId = QueueEntryId("queue-1"),
            idempotencyKey = "intent-1",
        )

        assertEquals(accepted.queueEntryId, duplicate.queueEntryId)
        assertEquals(accepted.idempotencyKey, duplicate.idempotencyKey)
    }

    private fun synchronizationRequest(): SynchronizationRequest =
        SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = io.dataloom.api.context.ExecutionContext(
                executionId = io.dataloom.api.identifier.ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        )

    private fun plan(
        disposition: StrategyDisposition,
        strategy: BuiltInSynchronizationStrategy,
        includeAdmission: Boolean = true,
        includeAtomicCapability: Boolean = true,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = strategy,
        effectiveProfileId = StrategyProfileId("profile-1"),
        effectiveStrategy = strategy,
        configurationVersion = StrategyConfigurationVersion(1),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = disposition,
        operations = if (includeAdmission) {
            listOf(StrategyOperation.ACCEPT_LOCAL, StrategyOperation.ENQUEUE_DURABLE_WORK)
        } else {
            listOf(StrategyOperation.ENQUEUE_DURABLE_WORK)
        },
        requiredCapabilities = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.QUEUE,
        ) + if (includeAdmission && includeAtomicCapability) {
            setOf(StrategyProviderCapability.ATOMIC_LOCAL_ADMISSION)
        } else {
            emptySet()
        },
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = if (disposition == StrategyDisposition.DEFER) {
            StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE
        } else {
            null
        },
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(StrategyOperation.READ_LOCAL, StrategyOperation.PUSH_REMOTE),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )
}
