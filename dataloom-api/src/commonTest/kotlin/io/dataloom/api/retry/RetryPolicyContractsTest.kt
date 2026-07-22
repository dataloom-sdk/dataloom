package io.dataloom.api.retry

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.scheduling.SchedulingDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryPolicyContractsTest {

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private val sampleRequest: SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-retry-001"),
        sessionId = SynchronizationSessionId("session-retry-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("exec-retry-001"),
            correlationId = CorrelationId("corr-retry-001"),
        ),
    )

    private val sampleError: DataLoomError = object : DataLoomError {
        override val code: ErrorCode = ErrorCode("NETWORK_TIMEOUT")
        override val category: ErrorCategory = ErrorCategory.NETWORK
        override val severity: ErrorSeverity = ErrorSeverity.WARNING
        override val recoverability: Recoverability = Recoverability.RECOVERABLE
        override val message: String = "Connection timed out"
        override val cause: Throwable? = null
    }

    private val nonRecoverableError: DataLoomError = object : DataLoomError {
        override val code: ErrorCode = ErrorCode("AUTH_INVALID")
        override val category: ErrorCategory = ErrorCategory.AUTHENTICATION
        override val severity: ErrorSeverity = ErrorSeverity.CRITICAL
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
        override val message: String = "Authentication credentials are invalid"
        override val cause: Throwable? = null
    }

    private val sampleOperation: RetryOperation = RetryOperation("transport.push")

    private val sampleAttempt: RetryAttempt = RetryAttempt(1)

    private val sampleDelay: SchedulingDelay = SchedulingDelay(5_000L)

    private val sampleProvider: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("sample-transport-provider"),
        name = ProviderName("Sample Transport"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("1.0.0"),
    )

    // -------------------------------------------------------------------------
    // RetryPolicyId tests
    // -------------------------------------------------------------------------

    @Test
    fun `retry policy id accepts valid value`() {
        val id: RetryPolicyId = RetryPolicyId("default-network-policy")
        assertEquals("default-network-policy", id.value)
    }

    @Test
    fun `retry policy id rejects blank value`() {
        assertFailsWith<IllegalArgumentException> {
            RetryPolicyId("")
        }
    }

    @Test
    fun `retry policy id rejects whitespace-only value`() {
        assertFailsWith<IllegalArgumentException> {
            RetryPolicyId("   ")
        }
    }

    @Test
    fun `retry policy id preserves exact value`() {
        val id: RetryPolicyId = RetryPolicyId("critical-upload-policy")
        assertEquals("critical-upload-policy", id.value)
    }

    @Test
    fun `retry policy id value equality`() {
        val a: RetryPolicyId = RetryPolicyId("manual-only-policy")
        val b: RetryPolicyId = RetryPolicyId("manual-only-policy")
        assertEquals(a, b)
    }

    @Test
    fun `retry policy id inequality for different values`() {
        val a: RetryPolicyId = RetryPolicyId("policy-a")
        val b: RetryPolicyId = RetryPolicyId("policy-b")
        assertNotEquals(a, b)
    }

    @Test
    fun `retry policy id toString returns wrapped value`() {
        val id: RetryPolicyId = RetryPolicyId("default-network-policy")
        assertEquals("default-network-policy", id.toString())
    }

    // -------------------------------------------------------------------------
    // RetryOperation tests
    // -------------------------------------------------------------------------

    @Test
    fun `retry operation accepts valid value`() {
        val operation: RetryOperation = RetryOperation("transport.push")
        assertEquals("transport.push", operation.value)
    }

    @Test
    fun `retry operation rejects blank value`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOperation("")
        }
    }

    @Test
    fun `retry operation rejects whitespace-only value`() {
        assertFailsWith<IllegalArgumentException> {
            RetryOperation("  ")
        }
    }

    @Test
    fun `retry operation preserves exact value`() {
        val operation: RetryOperation = RetryOperation("storage.apply-inbound")
        assertEquals("storage.apply-inbound", operation.value)
    }

    @Test
    fun `retry operation value equality`() {
        val a: RetryOperation = RetryOperation("transport.pull")
        val b: RetryOperation = RetryOperation("transport.pull")
        assertEquals(a, b)
    }

    @Test
    fun `retry operation inequality for different values`() {
        val a: RetryOperation = RetryOperation("transport.push")
        val b: RetryOperation = RetryOperation("transport.pull")
        assertNotEquals(a, b)
    }

    @Test
    fun `retry operation toString returns wrapped value`() {
        val operation: RetryOperation = RetryOperation("scheduler.schedule")
        assertEquals("scheduler.schedule", operation.toString())
    }

    @Test
    fun `retry operation accepts arbitrary application-defined values`() {
        val operation: RetryOperation = RetryOperation("custom-app.sync-invoices")
        assertEquals("custom-app.sync-invoices", operation.value)
    }

    // -------------------------------------------------------------------------
    // RetryAttempt tests
    // -------------------------------------------------------------------------

    @Test
    fun `retry attempt accepts positive number`() {
        val attempt: RetryAttempt = RetryAttempt(3)
        assertEquals(3, attempt.count)
    }

    @Test
    fun `retry attempt accepts number one`() {
        val attempt: RetryAttempt = RetryAttempt(1)
        assertEquals(1, attempt.count)
    }

    @Test
    fun `retry attempt rejects zero`() {
        assertFailsWith<IllegalArgumentException> {
            RetryAttempt(0)
        }
    }

    @Test
    fun `retry attempt rejects negative value`() {
        assertFailsWith<IllegalArgumentException> {
            RetryAttempt(-1)
        }
    }

    @Test
    fun `retry attempt value equality`() {
        val a: RetryAttempt = RetryAttempt(2)
        val b: RetryAttempt = RetryAttempt(2)
        assertEquals(a, b)
    }

    @Test
    fun `retry attempt inequality for different values`() {
        val a: RetryAttempt = RetryAttempt(1)
        val b: RetryAttempt = RetryAttempt(2)
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // RetryStopReason tests
    // -------------------------------------------------------------------------

    @Test
    fun `stop reason NON_RECOVERABLE exists`() {
        val reason: RetryStopReason = RetryStopReason.NON_RECOVERABLE
        assertEquals(RetryStopReason.NON_RECOVERABLE, reason)
    }

    @Test
    fun `stop reason ATTEMPT_LIMIT_REACHED exists`() {
        val reason: RetryStopReason = RetryStopReason.ATTEMPT_LIMIT_REACHED
        assertEquals(RetryStopReason.ATTEMPT_LIMIT_REACHED, reason)
    }

    @Test
    fun `stop reason POLICY_REJECTED exists`() {
        val reason: RetryStopReason = RetryStopReason.POLICY_REJECTED
        assertEquals(RetryStopReason.POLICY_REJECTED, reason)
    }

    @Test
    fun `stop reason UNSUPPORTED_OPERATION exists`() {
        val reason: RetryStopReason = RetryStopReason.UNSUPPORTED_OPERATION
        assertEquals(RetryStopReason.UNSUPPORTED_OPERATION, reason)
    }

    @Test
    fun `stop reason values are distinct`() {
        val reasons: Set<RetryStopReason> = setOf(
            RetryStopReason.NON_RECOVERABLE,
            RetryStopReason.ATTEMPT_LIMIT_REACHED,
            RetryStopReason.POLICY_REJECTED,
            RetryStopReason.UNSUPPORTED_OPERATION,
        )
        assertEquals(4, reasons.size)
    }

    @Test
    fun `stop reason can be matched without ordinal dependency`() {
        val reason: RetryStopReason = RetryStopReason.ATTEMPT_LIMIT_REACHED
        val matched: Boolean = when (reason) {
            RetryStopReason.NON_RECOVERABLE -> false
            RetryStopReason.ATTEMPT_LIMIT_REACHED -> true
            RetryStopReason.POLICY_REJECTED -> false
            RetryStopReason.UNSUPPORTED_OPERATION -> false
        }
        assertTrue(matched)
    }

    // -------------------------------------------------------------------------
    // RetryEvaluationRequest tests
    // -------------------------------------------------------------------------

    @Test
    fun `evaluation request preserves synchronization request`() {
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        assertEquals(sampleRequest, request.synchronizationRequest)
    }

    @Test
    fun `evaluation request preserves operation`() {
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        assertEquals(sampleOperation, request.operation)
    }

    @Test
    fun `evaluation request preserves error`() {
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        assertEquals(sampleError, request.error)
    }

    @Test
    fun `evaluation request preserves attempt`() {
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        assertEquals(sampleAttempt, request.attempt)
    }

    @Test
    fun `evaluation request previousDelay may be absent`() {
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        assertNull(request.previousDelay)
    }

    @Test
    fun `evaluation request preserves previousDelay when supplied`() {
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = sampleDelay,
            provider = null,
        )
        assertEquals(sampleDelay, request.previousDelay)
    }

    @Test
    fun `evaluation request provider may be absent`() {
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        assertNull(request.provider)
    }

    @Test
    fun `evaluation request preserves provider when supplied`() {
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = sampleProvider,
        )
        assertEquals(sampleProvider, request.provider)
    }

    @Test
    fun `evaluation request metadata defaults to empty`() {
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        assertTrue(request.metadata.isEmpty())
    }

    @Test
    fun `evaluation request preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("source" to "test"))
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
            metadata = metadata,
        )
        assertEquals(metadata, request.metadata)
    }

    @Test
    fun `equal evaluation requests compare as equal`() {
        val a: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = sampleDelay,
            provider = sampleProvider,
        )
        val b: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = sampleDelay,
            provider = sampleProvider,
        )
        assertEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // RetryDecision tests
    // -------------------------------------------------------------------------

    @Test
    fun `retry decision preserves delay`() {
        val decision: RetryDecision = RetryDecision.Retry(delay = sampleDelay)
        assertIs<RetryDecision.Retry>(decision)
        assertEquals(sampleDelay, decision.delay)
    }

    @Test
    fun `retry decision supports zero delay`() {
        val decision: RetryDecision = RetryDecision.Retry(delay = SchedulingDelay.ZERO)
        assertIs<RetryDecision.Retry>(decision)
        assertEquals(SchedulingDelay.ZERO, decision.delay)
        assertEquals(0L, decision.delay.milliseconds)
    }

    @Test
    fun `retry decision metadata defaults to empty`() {
        val decision: RetryDecision.Retry = RetryDecision.Retry(delay = sampleDelay)
        assertTrue(decision.metadata.isEmpty())
    }

    @Test
    fun `retry decision preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("reason" to "network-error"))
        val decision: RetryDecision.Retry = RetryDecision.Retry(
            delay = sampleDelay,
            metadata = metadata,
        )
        assertEquals(metadata, decision.metadata)
    }

    @Test
    fun `stop decision preserves reason`() {
        val decision: RetryDecision = RetryDecision.Stop(reason = RetryStopReason.NON_RECOVERABLE)
        assertIs<RetryDecision.Stop>(decision)
        assertEquals(RetryStopReason.NON_RECOVERABLE, decision.reason)
    }

    @Test
    fun `stop decision metadata defaults to empty`() {
        val decision: RetryDecision.Stop = RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
        assertTrue(decision.metadata.isEmpty())
    }

    @Test
    fun `stop decision preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("policy" to "manual-only"))
        val decision: RetryDecision.Stop = RetryDecision.Stop(
            reason = RetryStopReason.POLICY_REJECTED,
            metadata = metadata,
        )
        assertEquals(metadata, decision.metadata)
    }

    @Test
    fun `retry and stop are distinct decision types`() {
        val retry: RetryDecision = RetryDecision.Retry(delay = sampleDelay)
        val stop: RetryDecision = RetryDecision.Stop(reason = RetryStopReason.ATTEMPT_LIMIT_REACHED)
        assertIs<RetryDecision.Retry>(retry)
        assertIs<RetryDecision.Stop>(stop)
        assertNotEquals<RetryDecision>(retry, stop)
    }

    @Test
    fun `retry decisions with equal delays and metadata are equal`() {
        val a: RetryDecision.Retry = RetryDecision.Retry(delay = sampleDelay)
        val b: RetryDecision.Retry = RetryDecision.Retry(delay = sampleDelay)
        assertEquals(a, b)
    }

    @Test
    fun `stop decisions with equal reasons and metadata are equal`() {
        val a: RetryDecision.Stop = RetryDecision.Stop(reason = RetryStopReason.NON_RECOVERABLE)
        val b: RetryDecision.Stop = RetryDecision.Stop(reason = RetryStopReason.NON_RECOVERABLE)
        assertEquals(a, b)
    }

    @Test
    fun `retry decision sealed type is exhaustively matchable`() {
        val decision: RetryDecision = RetryDecision.Retry(delay = sampleDelay)
        val label: String = when (decision) {
            is RetryDecision.Retry -> "retry"
            is RetryDecision.Stop -> "stop"
        }
        assertEquals("retry", label)
    }

    @Test
    fun `stop decision sealed type is exhaustively matchable`() {
        val decision: RetryDecision = RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
        val label: String = when (decision) {
            is RetryDecision.Retry -> "retry"
            is RetryDecision.Stop -> "stop"
        }
        assertEquals("stop", label)
    }

    // -------------------------------------------------------------------------
    // RetryPolicy interface tests
    // -------------------------------------------------------------------------

    private class AlwaysRetryPolicy(
        override val id: RetryPolicyId = RetryPolicyId("always-retry-test-policy"),
        private val fixedDelay: SchedulingDelay = SchedulingDelay.ZERO,
    ) : RetryPolicy {
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Retry(delay = fixedDelay)
    }

    private class AlwaysStopPolicy(
        override val id: RetryPolicyId = RetryPolicyId("always-stop-test-policy"),
    ) : RetryPolicy {
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Stop(reason = RetryStopReason.POLICY_REJECTED)
    }

    private class DelayedRetryPolicy(
        override val id: RetryPolicyId = RetryPolicyId("delayed-retry-test-policy"),
        private val delay: SchedulingDelay,
    ) : RetryPolicy {
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Retry(delay = delay)
    }

    private class RecoverabilityAwarePolicy(
        override val id: RetryPolicyId = RetryPolicyId("recoverability-aware-test-policy"),
    ) : RetryPolicy {
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
            return when (request.error.recoverability) {
                Recoverability.NON_RECOVERABLE ->
                    RetryDecision.Stop(reason = RetryStopReason.NON_RECOVERABLE)
                Recoverability.RECOVERABLE, Recoverability.UNKNOWN ->
                    RetryDecision.Retry(delay = SchedulingDelay(1_000L))
            }
        }
    }

    @Test
    fun `policy id is exposed`() {
        val policy: RetryPolicy = AlwaysRetryPolicy(id = RetryPolicyId("test-policy-identifier"))
        assertEquals(RetryPolicyId("test-policy-identifier"), policy.id)
    }

    @Test
    fun `policy can return immediate retry`() {
        val policy: RetryPolicy = AlwaysRetryPolicy(fixedDelay = SchedulingDelay.ZERO)
        val evaluationRequest: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        val decision: RetryDecision = policy.evaluate(evaluationRequest)
        assertIs<RetryDecision.Retry>(decision)
        assertEquals(SchedulingDelay.ZERO, decision.delay)
    }

    @Test
    fun `policy can return delayed retry`() {
        val configuredDelay: SchedulingDelay = SchedulingDelay(30_000L)
        val policy: RetryPolicy = DelayedRetryPolicy(delay = configuredDelay)
        val evaluationRequest: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        val decision: RetryDecision = policy.evaluate(evaluationRequest)
        assertIs<RetryDecision.Retry>(decision)
        assertEquals(configuredDelay, decision.delay)
    }

    @Test
    fun `policy can stop retrying`() {
        val policy: RetryPolicy = AlwaysStopPolicy()
        val evaluationRequest: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        val decision: RetryDecision = policy.evaluate(evaluationRequest)
        assertIs<RetryDecision.Stop>(decision)
        assertEquals(RetryStopReason.POLICY_REJECTED, decision.reason)
    }

    @Test
    fun `policy evaluation is deterministic for identical requests`() {
        val policy: RetryPolicy = RecoverabilityAwarePolicy()
        val evaluationRequest: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        val first: RetryDecision = policy.evaluate(evaluationRequest)
        val second: RetryDecision = policy.evaluate(evaluationRequest)
        assertEquals(first, second)
    }

    @Test
    fun `policy returns stop for non-recoverable error`() {
        val policy: RetryPolicy = RecoverabilityAwarePolicy()
        val evaluationRequest: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = nonRecoverableError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        val decision: RetryDecision = policy.evaluate(evaluationRequest)
        assertIs<RetryDecision.Stop>(decision)
        assertEquals(RetryStopReason.NON_RECOVERABLE, decision.reason)
    }

    @Test
    fun `policy returns retry for recoverable error`() {
        val policy: RetryPolicy = RecoverabilityAwarePolicy()
        val evaluationRequest: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = sampleOperation,
            error = sampleError,
            attempt = sampleAttempt,
            previousDelay = null,
            provider = null,
        )
        val decision: RetryDecision = policy.evaluate(evaluationRequest)
        assertIs<RetryDecision.Retry>(decision)
    }

    @Test
    fun `policy interface requires no android or platform-specific type`() {
        // This test verifies the interface is usable with standard Kotlin types only.
        // The test itself uses no Android APIs, WorkManager, or platform-specific imports.
        val policy: RetryPolicy = AlwaysRetryPolicy()
        val request: RetryEvaluationRequest = RetryEvaluationRequest(
            synchronizationRequest = sampleRequest,
            operation = RetryOperation("storage.write-checkpoint"),
            error = sampleError,
            attempt = RetryAttempt(5),
            previousDelay = SchedulingDelay(10_000L),
            provider = sampleProvider,
            metadata = DataLoomMetadata.of(mapOf("context" to "platform-neutral-test")),
        )
        val decision: RetryDecision = policy.evaluate(request)
        assertIs<RetryDecision.Retry>(decision)
    }
}
