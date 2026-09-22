package io.dataloom.runtime.observation.operational

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.policy.PolicyCheckEvidence
import io.dataloom.api.policy.PolicyCheckOutcome
import io.dataloom.api.policy.PolicyDecision
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.worker.QueueWorkerSchedulingResult
import io.dataloom.runtime.worker.QueueWorkerWakeUpPlan
import io.dataloom.runtime.worker.QueueWorkerWakeUpReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-mapping tests for [PolicyDecisionOperationalEventBridge] and [QueueWorkerSchedulingOperationalEventBridge]. */
class PolicyAndSchedulerOperationalEventBridgesTest {

    private val at = DataLoomInstant(42_000L)
    private val REDACTED = "[REDACTED]"

    // ---- policy decisions ------------------------------------------------------------------------

    private data class PolicyCase(val name: String, val outcome: PolicyCheckOutcome, val type: String, val outcomeAttribute: String)

    @Test
    fun everyPolicyOutcomeMapsToItsEventTypeAndClosedOutcomeAttribute() {
        val cases = listOf(
            PolicyCase("allow", PolicyCheckOutcome.Allow("ok"), "dataloom.policy.decision.allowed", "ALLOW"),
            PolicyCase("deny", PolicyCheckOutcome.Deny("no"), "dataloom.policy.decision.denied", "DENY"),
            PolicyCase("user action", PolicyCheckOutcome.RequireUserAction("ask"), "dataloom.policy.decision.user_action_required", "REQUIRE_USER_ACTION"),
            PolicyCase("defer", PolicyCheckOutcome.Defer(SchedulingDelay(1_500L), "later"), "dataloom.policy.decision.deferred", "DEFER"),
        )

        cases.forEach { case ->
            val envelope = PolicyDecisionOperationalEventBridge.toEnvelope(context(), WorkflowId("wf"), decision(case.outcome), at)
            assertEquals(case.type, envelope.type.value, case.name)
            assertEquals(case.outcomeAttribute, envelope.attributes["policy.outcome"], case.name)
            assertEquals(OperationalEventCategory.AUDIT, envelope.category, case.name)
            assertEquals(at, envelope.occurredAt, case.name)
        }
    }

    @Test
    fun deferCarriesItsDelayAndOtherOutcomesDoNot() {
        val defer = PolicyDecisionOperationalEventBridge.toEnvelope(
            context(), null, decision(PolicyCheckOutcome.Defer(SchedulingDelay(1_500L), "later")), at,
        )
        val allow = PolicyDecisionOperationalEventBridge.toEnvelope(context(), null, decision(PolicyCheckOutcome.Allow("ok")), at)

        assertEquals("1500", defer.attributes["policy.deferDelayMilliseconds"])
        assertNull(allow.attributes["policy.deferDelayMilliseconds"])
    }

    @Test
    fun identityAndRoutingComeFromTheExecutionContextAndRequest() {
        val envelope = PolicyDecisionOperationalEventBridge.toEnvelope(
            context(), WorkflowId("wf-9"), decision(PolicyCheckOutcome.Allow("ok")), at,
        )

        assertEquals("policy.decision.set-1.exec-1", envelope.id.value)
        assertEquals(CorrelationId("corr-1"), envelope.correlationId)
        assertEquals(TraceId("trace-1"), envelope.traceId)
        assertEquals(TenantId("tenant-1"), envelope.tenantId)
        assertEquals(WorkflowId("wf-9"), envelope.workflowId)
        assertEquals("dataloom.runtime.policy.decision", envelope.source.value)
    }

    @Test
    fun theSameDecisionMapsToTheSameEnvelopeSoAReportIsIdempotent() {
        val one = PolicyDecisionOperationalEventBridge.toEnvelope(context(), null, decision(PolicyCheckOutcome.Deny("no")), at)
        val two = PolicyDecisionOperationalEventBridge.toEnvelope(context(), null, decision(PolicyCheckOutcome.Deny("no")), at)

        assertEquals(one, two)
    }

    @Test
    fun theFreeTextJustificationAndCheckMetadataNeverReachTheEnvelope() {
        val secret = "user jane.doe@example.test failed residency rule 7f3a-secret"
        val envelope = PolicyDecisionOperationalEventBridge.toEnvelope(
            context(), null, decision(PolicyCheckOutcome.Deny(secret)), at,
        )

        assertNull(envelope.attributes["policy.justification"]) // CONFIDENTIAL: removed outright
        envelope.attributes.entries.values.forEach { value ->
            assertTrue(!value.contains("secret") && !value.contains("jane"), "leaked: $value")
        }
    }

    @Test
    fun evidenceIsReportedAsCheckIdsWithClosedOutcomesAndIsBounded() {
        val many = (1..200).map { PolicyCheckEvidence(PolicyCheckId("check-$it"), PolicyCheckOutcome.Allow("ok")) }
        val envelope = PolicyDecisionOperationalEventBridge.toEnvelope(
            context(), null,
            PolicyDecision(PolicySetId("set-1"), PolicyCheckOutcome.Deny("no"), PolicyCheckId("check-9"), many),
            at,
        )

        assertEquals("200", envelope.attributes["policy.evidenceCount"])
        // INTERNAL ids and the evidence list are masked by the default policy; they are present, never raw.
        assertEquals(REDACTED, envelope.attributes["policy.winningCheckId"])
        assertEquals(REDACTED, envelope.attributes["policy.evidence"])
    }

    @Test
    fun unsafeIdCharactersAreSanitizedAndTheIdIsBounded() {
        val envelope = PolicyDecisionOperationalEventBridge.toEnvelope(
            ExecutionContext(ExecutionId("exec with spaces/😀".repeat(20)), CorrelationId("c")),
            null,
            PolicyDecision(PolicySetId("set|1"), PolicyCheckOutcome.Allow("ok"), null, emptyList()),
            at,
        )

        assertTrue(envelope.id.value.length <= 128)
        assertTrue(envelope.id.value.all { it.isLetterOrDigit() || it in "._:/-" })
    }

    // ---- scheduler wake-ups ------------------------------------------------------------------------

    @Test
    fun aWakeUpThatWasNotRequiredProducesNoEnvelope() {
        assertNull(QueueWorkerSchedulingOperationalEventBridge.toEnvelope(lease(), QueueWorkerSchedulingResult.NotRequired, at))
    }

    private data class WakeUpCase(
        val name: String,
        val result: QueueWorkerSchedulingResult,
        val type: String,
        val resultAttribute: String,
        val extra: Map<String, String?>,
    )

    @Test
    fun everySchedulingResultMapsToItsEventTypeAndClosedAttributes() {
        val failure = fakeError("SCHED-DOWN")
        val receipt = ScheduleReceipt(ScheduleId("receipt-1"))
        val protectedOk = CircuitBreakerExecutionResult.Executed(
            CircuitProtectedOperationResult.Success(receipt), CircuitBreakerRecordResult.Ignored,
        )
        val cases = listOf(
            WakeUpCase(
                "scheduled", QueueWorkerSchedulingResult.Scheduled(receipt, plan()),
                "dataloom.scheduler.wakeup.scheduled", "SCHEDULED", mapOf("wakeup.receiptScheduleId" to REDACTED),
            ),
            WakeUpCase(
                "not configured", QueueWorkerSchedulingResult.SchedulerNotConfigured(plan()),
                "dataloom.scheduler.wakeup.not_configured", "SCHEDULER_NOT_CONFIGURED", emptyMap(),
            ),
            WakeUpCase(
                "failed", QueueWorkerSchedulingResult.SchedulerFailed(failure, plan()),
                "dataloom.scheduler.wakeup.failed", "SCHEDULER_FAILED",
                mapOf("wakeup.error.code" to "SCHED-DOWN", "wakeup.error.category" to "STORAGE", "wakeup.error.message" to null),
            ),
            WakeUpCase(
                "circuit executed ok", QueueWorkerSchedulingResult.CircuitProtected(protectedOk, plan()),
                "dataloom.scheduler.wakeup.scheduled", "CIRCUIT_PROTECTED",
                mapOf("wakeup.circuit.outcome" to "EXECUTED_SUCCEEDED", "wakeup.receiptScheduleId" to REDACTED),
            ),
            WakeUpCase(
                "circuit rejected",
                QueueWorkerSchedulingResult.CircuitProtected(
                    CircuitBreakerExecutionResult.Rejected(CircuitBreakerRejectionReason.OPEN, DataLoomInstant(99_000L)), plan(),
                ),
                "dataloom.scheduler.wakeup.circuit_rejected", "CIRCUIT_PROTECTED",
                mapOf(
                    "wakeup.circuit.outcome" to "REJECTED",
                    "wakeup.circuit.rejectionReason" to "OPEN",
                    "wakeup.circuit.retryAtEpochMillis" to "99000",
                ),
            ),
            WakeUpCase(
                "circuit permission contention",
                QueueWorkerSchedulingResult.CircuitProtected(CircuitBreakerExecutionResult.PermissionContentionLimitReached, plan()),
                "dataloom.scheduler.wakeup.failed", "CIRCUIT_PROTECTED",
                mapOf("wakeup.circuit.outcome" to "PERMISSION_CONTENTION_LIMIT"),
            ),
            WakeUpCase(
                "circuit permission persistence failure",
                QueueWorkerSchedulingResult.CircuitProtected(CircuitBreakerExecutionResult.PermissionPersistenceFailure(failure), plan()),
                "dataloom.scheduler.wakeup.failed", "CIRCUIT_PROTECTED",
                mapOf("wakeup.circuit.outcome" to "PERMISSION_PERSISTENCE_FAILURE", "wakeup.error.code" to "SCHED-DOWN"),
            ),
        )

        cases.forEach { case ->
            val envelope = assertNotNull(
                QueueWorkerSchedulingOperationalEventBridge.toEnvelope(lease(), case.result, at),
                case.name,
            )
            assertEquals(case.type, envelope.type.value, case.name)
            assertEquals(case.resultAttribute, envelope.attributes["wakeup.result"], case.name)
            case.extra.forEach { (key, expected) -> assertEquals(expected, envelope.attributes[key], "${case.name}: $key") }
            assertEquals(OperationalEventCategory.SYSTEM, envelope.category, case.name)
        }
    }

    @Test
    fun planFieldsAndIdentityComeFromThePlanAndTheRunsLease() {
        val envelope = assertNotNull(
            QueueWorkerSchedulingOperationalEventBridge.toEnvelope(
                lease("lease with/odd chars"), QueueWorkerSchedulingResult.SchedulerNotConfigured(plan()), at,
            ),
        )

        assertEquals("scheduler.wakeup.lease_with/odd_chars", envelope.id.value)
        assertEquals("ACQUISITION_LIMIT_REACHED", envelope.attributes["wakeup.reason"])
        assertEquals("30000", envelope.attributes["wakeup.delayMilliseconds"])
        assertEquals(REDACTED, envelope.attributes["wakeup.scheduleId"]) // INTERNAL ids are masked by the default policy
        assertEquals("REPLACE", envelope.attributes["wakeup.existingSchedulePolicy"])
        assertEquals(at, envelope.occurredAt)
        assertEquals("dataloom.runtime.scheduler.wakeup", envelope.source.value)
    }

    @Test
    fun aSchedulerErrorMessageNeverReachesTheEnvelope() {
        val envelope = assertNotNull(
            QueueWorkerSchedulingOperationalEventBridge.toEnvelope(
                lease(), QueueWorkerSchedulingResult.SchedulerFailed(fakeError("X", "token=SECRET123 leaked"), plan()), at,
            ),
        )

        envelope.attributes.entries.values.forEach { assertTrue(!it.contains("SECRET123"), "leaked: $it") }
        assertNull(envelope.attributes["wakeup.error.message"])
    }

    // ---- fixtures ---------------------------------------------------------------------------------------

    private fun context() = ExecutionContext(
        executionId = ExecutionId("exec-1"),
        correlationId = CorrelationId("corr-1"),
        traceId = TraceId("trace-1"),
        tenantId = TenantId("tenant-1"),
    )

    private fun decision(outcome: PolicyCheckOutcome) = PolicyDecision(
        policySetId = PolicySetId("set-1"),
        outcome = outcome,
        winningCheckId = PolicyCheckId("check-1"),
        evidence = listOf(PolicyCheckEvidence(PolicyCheckId("check-1"), outcome)),
    )

    private fun lease(value: String = "lease-1") = QueueLeaseId(value)

    private fun plan() = QueueWorkerWakeUpPlan.Schedule(
        reason = QueueWorkerWakeUpReason.ACQUISITION_LIMIT_REACHED,
        delay = SchedulingDelay(30_000L),
        scheduleId = ScheduleId("worker-schedule"),
        constraints = ScheduleConstraints(),
        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
    )

    private fun fakeError(code: String, message: String = "raw sensitive message"): DataLoomError = object : DataLoomError {
        override val code = ErrorCode(code)
        override val category = ErrorCategory.STORAGE
        override val severity = ErrorSeverity.ERROR
        override val recoverability = Recoverability.RECOVERABLE
        override val message = message
        override val cause: Throwable? = null
    }
}
