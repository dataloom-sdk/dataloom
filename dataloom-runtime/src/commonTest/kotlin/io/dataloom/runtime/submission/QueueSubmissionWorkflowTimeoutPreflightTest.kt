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
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QueueSubmissionWorkflowTimeoutPreflightTest {

    @Test
    fun `matching workflow timeout evidence is accepted`() {
        val state = timeoutState(deadline = 5_000L)
        val submission = submission(state)
        val preflight = QueueSubmissionPreflight(
            encoder = encoder(entry(state)),
        )

        assertIs<QueueSubmissionPreflightResult.Ready>(preflight.prepare(submission))
    }

    @Test
    fun `changed workflow deadline is rejected before provider policy`() {
        val submission = submission(timeoutState(deadline = 5_000L))
        val preflight = QueueSubmissionPreflight(
            encoder = encoder(entry(timeoutState(deadline = 6_000L))),
        )

        val rejected = assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            preflight.prepare(submission),
        )

        assertEquals("DL-Q-SUBMISSION-CONTRACT-VIOLATION", rejected.error.code.value)
        assertEquals(submission.queueEntryId, rejected.queueEntryId)
    }

    @Test
    fun `encoder cannot silently drop workflow timeout evidence`() {
        val submission = submission(timeoutState(deadline = 5_000L))
        val preflight = QueueSubmissionPreflight(
            encoder = encoder(entry(null)),
        )

        assertIs<QueueSubmissionPreflightResult.ContractViolation>(
            preflight.prepare(submission),
        )
    }

    private fun encoder(entry: QueueEntry): QueuedSynchronizationWorkEncoder =
        QueuedSynchronizationWorkEncoder {
            QueuedSynchronizationWorkEncodingResult.Encoded(
                QueueEnqueueRequest(entry),
            )
        }

    private fun timeoutState(deadline: Long): WorkflowTimeoutState = WorkflowTimeoutState(
        startedAt = DataLoomInstant(1_000L),
        deadline = DataLoomInstant(deadline),
    )

    private fun submission(state: WorkflowTimeoutState): QueuedSynchronizationSubmission =
        QueuedSynchronizationSubmission(
            queueEntryId = QueueEntryId("entry-1"),
            work = work(),
            availableAt = DataLoomInstant(1_000L),
            workflowTimeoutState = state,
        )

    private fun entry(state: WorkflowTimeoutState?): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
        synchronizationRequest = request(),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        workflowTimeoutState = state,
    )

    private fun work(): QueuedSynchronizationWork = QueuedSynchronizationWork(
        request = request(),
        bindings = SynchronizationProviderBindings(
            storageProviderId = io.dataloom.api.provider.ProviderId("storage-1"),
            transportProviderId = io.dataloom.api.provider.ProviderId("transport-1"),
        ),
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
