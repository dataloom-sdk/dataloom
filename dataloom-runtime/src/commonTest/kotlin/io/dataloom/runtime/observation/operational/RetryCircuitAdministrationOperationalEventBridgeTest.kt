package io.dataloom.runtime.observation.operational

import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCommandState
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationFailureSnapshot
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitAdministrationStateRecord
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationId
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationCommandState
import io.dataloom.api.retry.RetryAdministrationCommandStatus
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAdministrationStateRecord
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitAdministrationResult
import io.dataloom.runtime.retry.RetryAdministrationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [RetryCircuitAdministrationOperationalEventBridge] maps every
 * [RetryAdministrationResult]/[CircuitAdministrationResult] terminal outcome
 * to a sane [io.dataloom.api.operational.OperationalEventEnvelope], and that
 * field classification never leaks a raw sensitive value.
 */
class RetryCircuitAdministrationOperationalEventBridgeTest {

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-sensitive-message-should-never-appear",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private fun retryRequest(
        commandIdValue: String = "retry-command-001",
        principalIdValue: String = "operator-should-be-masked",
        queueEntryIdValue: String = "queue-entry-should-be-masked",
    ): RetryAdministrationRequest = RetryAdministrationRequest(
        commandId = RetryAdministrationCommandId(commandIdValue),
        queueEntryId = QueueEntryId(queueEntryIdValue),
        principalId = RetryAdministrationPrincipalId(principalIdValue),
        requestedAt = DataLoomInstant(1_000L),
        action = RetryAdministrationAction.REQUEUE,
        reason = RetryAdministrationReason("raw-reason-text-should-never-appear"),
        originalFailure = RetryFailureSnapshot(
            code = ErrorCode("NETWORK_FINAL"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
        ),
    )

    private fun retrySucceeded(request: RetryAdministrationRequest): RetryAdministrationResult.Succeeded =
        RetryAdministrationResult.Succeeded(
            RetryAdministrationStateRecord(
                state = RetryAdministrationCommandState(
                    request = request,
                    status = RetryAdministrationCommandStatus.SUCCEEDED,
                    authorizationId = RetryAdministrationAuthorizationId("authorization-should-be-masked"),
                    effectiveRecoverability = Recoverability.RECOVERABLE,
                    updatedAt = DataLoomInstant(2_000L),
                ),
                version = 1L,
            ),
        )

    private fun circuitRequest(
        commandIdValue: String = "circuit-command-001",
        principalIdValue: String = "operator-should-be-masked",
        tenantId: TenantId? = null,
        workflowId: WorkflowId? = null,
    ): CircuitAdministrationRequest = CircuitAdministrationRequest(
        commandId = CircuitAdministrationCommandId(commandIdValue),
        scope = when {
            workflowId != null -> CircuitBreakerScope.workflow(workflowId)
            tenantId != null -> CircuitBreakerScope.tenantProviderOperation(
                tenantId = tenantId,
                providerId = ProviderId("provider-should-be-masked"),
                operation = io.dataloom.api.retry.RetryOperation("transport.push"),
            )
            else -> CircuitBreakerScope.global()
        },
        principalId = CircuitAdministrationPrincipalId(principalIdValue),
        requestedAt = DataLoomInstant(1_000L),
        action = CircuitAdministrationAction.RESET,
        reason = CircuitAdministrationReason("raw-reason-text-should-never-appear"),
    )

    private fun circuitSucceeded(request: CircuitAdministrationRequest): CircuitAdministrationResult.Succeeded =
        CircuitAdministrationResult.Succeeded(
            CircuitAdministrationStateRecord(
                state = CircuitAdministrationCommandState(
                    request = request,
                    status = CircuitAdministrationCommandStatus.SUCCEEDED,
                    authorizationId = CircuitAdministrationAuthorizationId("authorization-should-be-masked"),
                    updatedAt = DataLoomInstant(2_000L),
                    resultingRecord = CircuitBreakerStateRecord(
                        state = CircuitBreakerState(
                            scope = request.scope,
                            phase = CircuitBreakerPhase.CLOSED,
                            consecutiveFailures = 0,
                            failureWindowStartedAt = null,
                            openUntil = null,
                            probeGeneration = 0L,
                            probeInFlight = false,
                            updatedAt = DataLoomInstant(2_000L),
                        ),
                        version = 1L,
                    ),
                ),
                version = 1L,
            ),
        )

    // -------------------------------------------------------------------------
    // Retry-administration: identity/routing
    // -------------------------------------------------------------------------

    @Test
    fun retryToEnvelope_reusesCommandIdAsCorrelationId_neverInventsOne() {
        val request = retryRequest(commandIdValue = "retry-corr-001")
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, retrySucceeded(request))
        assertEquals(CorrelationId("retry-corr-001"), envelope.correlationId)
    }

    @Test
    fun retryToEnvelope_reusesDurableStateUpdatedAt_neverReadsAClock() {
        val request = retryRequest()
        val result = retrySucceeded(request)
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, result)
        assertEquals(result.record.state.updatedAt, envelope.occurredAt)
    }

    @Test
    fun retryToEnvelope_fallsBackToRequestedAt_whenResultCarriesNoDurableState() {
        val request = retryRequest()
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(
            request,
            RetryAdministrationResult.ContentionLimitReached,
        )
        assertEquals(request.requestedAt, envelope.occurredAt)
    }

    @Test
    fun retryToEnvelope_category_isAudit() {
        val request = retryRequest()
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, retrySucceeded(request))
        assertEquals(OperationalEventCategory.AUDIT, envelope.category)
    }

    @Test
    fun retryToEnvelope_prefixesIdWithRetryDomain_andSanitizesDisallowedCharacters() {
        val request = retryRequest(commandIdValue = "retry command 1!#weird")
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, retrySucceeded(request))
        assertTrue(envelope.id.value.startsWith("retry."))
        assertTrue(envelope.id.value.none { it == ' ' || it == '!' || it == '#' })
    }

    @Test
    fun retryToEnvelope_isPureAndDeterministic() {
        val request = retryRequest()
        val result = retrySucceeded(request)
        val first = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, result)
        val second = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, result)
        assertEquals(first, second)
    }

    // -------------------------------------------------------------------------
    // Retry-administration: classification
    // -------------------------------------------------------------------------

    @Test
    fun retryToEnvelope_masksPrincipalAndQueueEntryIdentifiers_neverKeepsRawValue() {
        val request = retryRequest(
            principalIdValue = "operator-should-be-masked",
            queueEntryIdValue = "queue-entry-should-be-masked",
        )
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, retrySucceeded(request))
        assertNotEqual("operator-should-be-masked", envelope.attributes["request.principalId"])
        assertNotEqual("queue-entry-should-be-masked", envelope.attributes["request.queueEntryId"])
    }

    @Test
    fun retryToEnvelope_neverIncludesFreeTextReason() {
        val request = retryRequest()
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, retrySucceeded(request))
        envelope.attributes.entries.values.forEach { value ->
            assertTrue(!value.contains("raw-reason-text-should-never-appear"))
        }
    }

    @Test
    fun retryToEnvelope_persistenceFailure_removesRawErrorMessage_keepsClosedFields() {
        val request = retryRequest()
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(
            request,
            RetryAdministrationResult.PersistenceFailure(FakeError()),
        )
        assertNull(envelope.attributes["result.error.message"])
        assertEquals("DL-FAKE", envelope.attributes["result.error.code"])
        assertEquals("STORAGE", envelope.attributes["result.error.category"])
        envelope.attributes.entries.values.forEach { value ->
            assertTrue(!value.contains("raw-sensitive-message-should-never-appear"))
        }
    }

    @Test
    fun retryToEnvelope_executionFailed_snapshotHasNoMessageField_soNothingIsRemoved() {
        val request = retryRequest()
        val result = RetryAdministrationResult.ExecutionFailed(
            RetryAdministrationStateRecord(
                state = RetryAdministrationCommandState(
                    request = request,
                    status = RetryAdministrationCommandStatus.EXECUTION_FAILED,
                    authorizationId = RetryAdministrationAuthorizationId("authorization-should-be-masked"),
                    effectiveRecoverability = Recoverability.RECOVERABLE,
                    updatedAt = DataLoomInstant(2_000L),
                    executionFailure = RetryFailureSnapshot(
                        code = ErrorCode("EXEC_FAIL"),
                        category = ErrorCategory.QUEUE,
                        severity = ErrorSeverity.ERROR,
                        recoverability = Recoverability.RECOVERABLE,
                    ),
                ),
                version = 1L,
            ),
        )
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, result)
        assertEquals("EXEC_FAIL", envelope.attributes["result.executionFailure.code"])
        assertEquals("QUEUE", envelope.attributes["result.executionFailure.category"])
    }

    @Test
    fun retryToEnvelope_authorizationDenied_masksRejectionReasonCode() {
        val request = retryRequest()
        val result = RetryAdministrationResult.AuthorizationDenied(
            RetryAdministrationStateRecord(
                state = RetryAdministrationCommandState(
                    request = request,
                    status = RetryAdministrationCommandStatus.AUTHORIZATION_DENIED,
                    authorizationId = null,
                    effectiveRecoverability = null,
                    updatedAt = DataLoomInstant(2_000L),
                    rejectionReasonCode = "NOT_ENTITLED",
                ),
                version = 1L,
            ),
        )
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, result)
        assertNotEqual("NOT_ENTITLED", envelope.attributes["result.rejectionReasonCode"])
    }

    // -------------------------------------------------------------------------
    // Circuit-administration: identity/routing
    // -------------------------------------------------------------------------

    @Test
    fun circuitToEnvelope_reusesCommandIdAsCorrelationId_prefixesEnvelopeIdWithCircuitDomain() {
        val request = circuitRequest(commandIdValue = "circuit-corr-001")
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, circuitSucceeded(request))
        assertEquals(CorrelationId("circuit-corr-001"), envelope.correlationId)
        assertTrue(envelope.id.value.startsWith("circuit."))
    }

    @Test
    fun circuitToEnvelope_reusesScopeTenantAndWorkflowIdentity_whenPresent() {
        val request = circuitRequest(workflowId = WorkflowId("workflow-001"))
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, circuitSucceeded(request))
        assertEquals(WorkflowId("workflow-001"), envelope.workflowId)
        assertNull(envelope.tenantId)
    }

    @Test
    fun circuitToEnvelope_omitsTenantAndWorkflow_forGlobalScope() {
        val request = circuitRequest()
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, circuitSucceeded(request))
        assertNull(envelope.tenantId)
        assertNull(envelope.workflowId)
    }

    @Test
    fun circuitToEnvelope_retryAndCircuitDomainsNeverCollide_forSameRawCommandId() {
        val sharedRawId = "shared-raw-id"
        val retryReq = retryRequest(commandIdValue = sharedRawId)
        val circuitReq = circuitRequest(commandIdValue = sharedRawId)
        val retryEnvelope =
            RetryCircuitAdministrationOperationalEventBridge.toEnvelope(retryReq, retrySucceeded(retryReq))
        val circuitEnvelope =
            RetryCircuitAdministrationOperationalEventBridge.toEnvelope(circuitReq, circuitSucceeded(circuitReq))
        assertTrue(retryEnvelope.id != circuitEnvelope.id)
    }

    @Test
    fun circuitToEnvelope_category_isAudit() {
        val request = circuitRequest()
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, circuitSucceeded(request))
        assertEquals(OperationalEventCategory.AUDIT, envelope.category)
    }

    // -------------------------------------------------------------------------
    // Circuit-administration: classification
    // -------------------------------------------------------------------------

    @Test
    fun circuitToEnvelope_executionRecordingUnconfirmed_removesRawPersistenceErrorMessage() {
        val request = circuitRequest()
        val executionResult = io.dataloom.api.circuit.CircuitAdministrationExecutionResult.Applied(
            CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = request.scope,
                    phase = CircuitBreakerPhase.CLOSED,
                    consecutiveFailures = 0,
                    failureWindowStartedAt = null,
                    openUntil = null,
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = DataLoomInstant(2_000L),
                ),
                version = 1L,
            ),
        )
        val result = CircuitAdministrationResult.ExecutionRecordingUnconfirmed(
            command = io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand(
                request = request,
                authorizationId = CircuitAdministrationAuthorizationId("authorization-should-be-masked"),
            ),
            executionResult = executionResult,
            persistenceError = FakeError(message = "raw-sensitive-message-should-never-appear"),
        )
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, result)
        assertNull(envelope.attributes["result.persistenceError.message"])
        assertEquals("DL-FAKE", envelope.attributes["result.persistenceError.code"])
        assertEquals(request.requestedAt, envelope.occurredAt)
    }

    @Test
    fun circuitToEnvelope_clockRegression_usesObservedAtNotPersistedAt_andRecordsPersistedAtAsAttribute() {
        val request = circuitRequest()
        val result = CircuitAdministrationResult.ClockRegression(
            observedAt = DataLoomInstant(500L),
            persistedAt = DataLoomInstant(9_000L),
        )
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, result)
        assertEquals(DataLoomInstant(500L), envelope.occurredAt)
        assertEquals("9000", envelope.attributes["result.persistedAtEpochMillis"])
    }

    @Test
    fun circuitToEnvelope_maskedIdentifiers_neverAppearRaw() {
        val request = circuitRequest(
            principalIdValue = "operator-should-be-masked",
            tenantId = TenantId("tenant-should-be-masked"),
        )
        val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, circuitSucceeded(request))
        assertNotEqual("operator-should-be-masked", envelope.attributes["request.principalId"])
        assertNotEqual("provider-should-be-masked", envelope.attributes["request.scope.providerId"])
        // Tenant identity is reused unmasked on the typed envelope field only, never duplicated into attributes.
        assertEquals(TenantId("tenant-should-be-masked"), envelope.tenantId)
        assertNull(envelope.attributes["request.scope.tenantId"])
    }

    private fun assertNotEqual(rawValue: String, redactedValue: String?) {
        assertNotNull(redactedValue)
        assertTrue(redactedValue != rawValue, "Expected '$redactedValue' to not equal raw value '$rawValue'.")
    }
}
