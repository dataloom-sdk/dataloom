package io.dataloom.runtime.submission

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import kotlin.test.Test
import kotlin.test.assertIs

class QueueSubmissionStrategyDecisionPreflightTest {

    @Test
    fun matchingStrategyDecisionIsAccepted() {
        val decision = decision()
        val submission = submission(decision)
        val preflight = QueueSubmissionPreflight(encoder(entry(decision)))

        assertIs<QueueSubmissionPreflightResult.Ready>(preflight.prepare(submission))
    }

    @Test
    fun changedStrategyDecisionIsRejectedBeforeProviderPolicy() {
        val submission = submission(decision(version = 3L))
        val preflight = QueueSubmissionPreflight(encoder(entry(decision(version = 4L))))

        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            preflight.prepare(submission),
        )
    }

    @Test
    fun encoderCannotDropStrategyDecision() {
        val submission = submission(decision())
        val preflight = QueueSubmissionPreflight(encoder(entry(null)))

        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            preflight.prepare(submission),
        )
    }

    @Test
    fun encoderCannotInventStrategyDecision() {
        val submission = submission(null)
        val preflight = QueueSubmissionPreflight(encoder(entry(decision())))

        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            preflight.prepare(submission),
        )
    }

    @Test
    fun matchingCompleteStrategyPlanIsAccepted() {
        val decision = decision()
        val plan = plan()
        val submission = submission(decision, plan)
        val preflight = QueueSubmissionPreflight(encoder(entry(decision, plan)))

        assertIs<QueueSubmissionPreflightResult.Ready>(preflight.prepare(submission))
    }

    @Test
    fun encoderCannotChangeDropOrInventStrategyPlan() {
        val decision = decision()
        val original = plan()
        val changed = plan(continuationOperation = StrategyOperation.RECONCILE)
        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            QueueSubmissionPreflight(encoder(entry(decision, changed)))
                .prepare(submission(decision, original)),
        )
        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            QueueSubmissionPreflight(encoder(entry(decision, null)))
                .prepare(submission(decision, original)),
        )
        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            QueueSubmissionPreflight(encoder(entry(decision, original)))
                .prepare(submission(decision, null)),
        )
    }

    private fun encoder(entry: QueueEntry): QueuedSynchronizationWorkEncoder =
        QueuedSynchronizationWorkEncoder {
            QueuedSynchronizationWorkEncodingResult.Encoded(QueueEnqueueRequest(entry))
        }

    private fun submission(
        decision: PersistedStrategyDecision?,
        plan: StrategyExecutionPlan? = null,
    ): QueuedSynchronizationSubmission = QueuedSynchronizationSubmission(
        queueEntryId = QueueEntryId("entry-1"),
        work = QueuedSynchronizationWork(
            request = request(),
            bindings = SynchronizationProviderBindings(
                storageProviderId = ProviderId("storage-1"),
                transportProviderId = ProviderId("transport-1"),
            ),
            strategyDecision = decision,
            strategyPlan = plan,
        ),
        availableAt = DataLoomInstant(1_000L),
    )

    private fun entry(
        decision: PersistedStrategyDecision?,
        plan: StrategyExecutionPlan? = null,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
        synchronizationRequest = request(),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        strategyDecision = decision,
        strategyPlan = plan,
    )

    private fun plan(
        continuationOperation: StrategyOperation = StrategyOperation.PUSH_REMOTE,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(3L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = if (continuationOperation == StrategyOperation.RECONCILE) {
                listOf(StrategyOperation.PUSH_REMOTE, StrategyOperation.RECONCILE)
            } else {
                listOf(StrategyOperation.PUSH_REMOTE)
            },
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )

    private fun decision(version: Long = 3L): PersistedStrategyDecision =
        PersistedStrategyDecision(
            decisionId = StrategyDecisionId("decision-1"),
            planId = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId("offline-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(version),
            disposition = StrategyDisposition.DEFER,
        )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    )
}
