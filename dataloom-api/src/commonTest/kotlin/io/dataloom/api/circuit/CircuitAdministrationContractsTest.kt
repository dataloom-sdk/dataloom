package io.dataloom.api.circuit

import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CircuitAdministrationContractsTest {
    @Test
    fun `reason and reason codes are bounded`() {
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationReason("")
        }
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationReason("x".repeat(513))
        }
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationAuthorizationDecision.Denied("")
        }
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationExecutionResult.Rejected("x".repeat(129))
        }
    }

    @Test
    fun `successful command requires resulting record for exact scope`() {
        val request = request(CircuitAdministrationAction.CLOSE)
        val resultingRecord = CircuitBreakerStateRecord(
            state = closedState(request.scope),
            version = 1L,
        )

        CircuitAdministrationCommandState(
            request = request,
            status = CircuitAdministrationCommandStatus.SUCCEEDED,
            authorizationId = CircuitAdministrationAuthorizationId("authorization-1"),
            updatedAt = DataLoomInstant(2L),
            resultingRecord = resultingRecord,
        )

        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationCommandState(
                request = request,
                status = CircuitAdministrationCommandStatus.SUCCEEDED,
                authorizationId = CircuitAdministrationAuthorizationId("authorization-1"),
                updatedAt = DataLoomInstant(2L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationCommandState(
                request = request,
                status = CircuitAdministrationCommandStatus.SUCCEEDED,
                authorizationId = CircuitAdministrationAuthorizationId("authorization-1"),
                updatedAt = DataLoomInstant(2L),
                resultingRecord = CircuitBreakerStateRecord(
                    state = closedState(CircuitBreakerScope.global()).copy(
                        scope = CircuitBreakerScope.workflow(
                            io.dataloom.api.identifier.WorkflowId("other-workflow"),
                        ),
                    ),
                    version = 1L,
                ),
            )
        }
    }

    @Test
    fun `authorization and terminal failure evidence match status`() {
        val request = request(CircuitAdministrationAction.RESET)

        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationCommandState(
                request = request,
                status = CircuitAdministrationCommandStatus.AUTHORIZED,
                authorizationId = null,
                updatedAt = DataLoomInstant(2L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationCommandState(
                request = request,
                status = CircuitAdministrationCommandStatus.EXECUTION_FAILED,
                authorizationId = CircuitAdministrationAuthorizationId("authorization-1"),
                updatedAt = DataLoomInstant(2L),
            )
        }
    }

    @Test
    fun `identities reject blank values without rendering wrappers`() {
        assertFailsWith<IllegalArgumentException> { CircuitAdministrationCommandId(" ") }
        assertFailsWith<IllegalArgumentException> { CircuitAdministrationPrincipalId(" ") }
        assertFailsWith<IllegalArgumentException> { CircuitAdministrationAuthorizationId(" ") }
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationCommandId("x".repeat(129))
        }
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationPrincipalId("x".repeat(129))
        }
        assertFailsWith<IllegalArgumentException> {
            CircuitAdministrationAuthorizationId("x".repeat(129))
        }
        assertTrue(CircuitAdministrationCommandId("command-1").toString() == "command-1")
    }

    private fun request(action: CircuitAdministrationAction): CircuitAdministrationRequest =
        CircuitAdministrationRequest(
            commandId = CircuitAdministrationCommandId("command-1"),
            scope = CircuitBreakerScope.provider(
                io.dataloom.api.provider.ProviderId("provider-1"),
            ),
            principalId = CircuitAdministrationPrincipalId("operator-1"),
            requestedAt = DataLoomInstant(1L),
            action = action,
            reason = CircuitAdministrationReason("bounded reason"),
        )

    private fun closedState(scope: CircuitBreakerScope): CircuitBreakerState =
        CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.CLOSED,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = null,
            probeGeneration = 0L,
            probeInFlight = false,
            updatedAt = DataLoomInstant(2L),
        )
}
