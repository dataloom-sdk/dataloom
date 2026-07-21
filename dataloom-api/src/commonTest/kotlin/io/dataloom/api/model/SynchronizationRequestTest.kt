package io.dataloom.api.model

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import kotlin.test.Test
import kotlin.test.assertEquals

class SynchronizationRequestTest {

    @Test
    fun `required values are preserved`() {
        val context: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        )

        val request: SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = context,
        )

        assertEquals("workflow-001", request.workflowId.value)
        assertEquals("session-001", request.sessionId.value)
        assertEquals(SynchronizationDirection.PUSH, request.direction)
        assertEquals(SynchronizationMode.DELTA, request.mode)
        assertEquals(context, request.context)
    }

    @Test
    fun `default priority is normal`() {
        val request: SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.FULL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-001"),
                correlationId = CorrelationId("corr-001"),
            ),
        )

        assertEquals(WorkflowPriority.NORMAL, request.priority)
    }

    @Test
    fun `explicit priority is preserved`() {
        val request: SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.CRITICAL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-001"),
                correlationId = CorrelationId("corr-001"),
            ),
        )

        assertEquals(WorkflowPriority.CRITICAL, request.priority)
    }

    @Test
    fun `equal requests compare as equal`() {
        val context: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        )
        val first: SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = SynchronizationDirection.BIDIRECTIONAL,
            mode = SynchronizationMode.FULL,
            context = context,
        )
        val second: SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = SynchronizationDirection.BIDIRECTIONAL,
            mode = SynchronizationMode.FULL,
            context = context,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `request construction causes no runtime action`() {
        val request: SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.FULL,
            context = ExecutionContext(
                executionId = ExecutionId("execution-001"),
                correlationId = CorrelationId("corr-001"),
            ),
        )

        assertEquals("workflow-001", request.workflowId.value)
    }
}
