package io.dataloom.api.circuit

import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CircuitBreakerContractsTest {
    private val providerId = ProviderId("provider-001")
    private val operation = RetryOperation("transport.push")

    @Test
    fun `scope factories preserve exact explicit identity`() {
        assertEquals(CircuitBreakerScopeKind.GLOBAL, CircuitBreakerScope.global().kind)
        assertEquals(providerId, CircuitBreakerScope.provider(providerId).providerId)
        assertEquals(
            operation,
            CircuitBreakerScope.providerOperation(providerId, operation).operation,
        )
        assertEquals(
            TenantId("tenant-001"),
            CircuitBreakerScope.tenantProviderOperation(
                tenantId = TenantId("tenant-001"),
                providerId = providerId,
                operation = operation,
            ).tenantId,
        )
        assertEquals(
            WorkflowId("workflow-001"),
            CircuitBreakerScope.workflow(WorkflowId("workflow-001")).workflowId,
        )
    }

    @Test
    fun `scope rejects fields that do not match kind`() {
        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerScope(
                kind = CircuitBreakerScopeKind.GLOBAL,
                providerId = providerId,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerScope(kind = CircuitBreakerScopeKind.PROVIDER)
        }
    }

    @Test
    fun `state enforces phase invariants`() {
        val scope = CircuitBreakerScope.provider(providerId)
        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerState(
                scope = scope,
                phase = CircuitBreakerPhase.OPEN,
                consecutiveFailures = 1,
                failureWindowStartedAt = DataLoomInstant(1L),
                openUntil = DataLoomInstant(10L),
                probeGeneration = 0L,
                probeInFlight = false,
                updatedAt = DataLoomInstant(2L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerState(
                scope = scope,
                phase = CircuitBreakerPhase.HALF_OPEN,
                consecutiveFailures = 0,
                failureWindowStartedAt = null,
                openUntil = null,
                probeGeneration = 1L,
                probeInFlight = false,
                updatedAt = DataLoomInstant(2L),
                probeLeaseUntil = DataLoomInstant(3L),
            )
        }
        val halfOpen = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.HALF_OPEN,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = null,
            probeGeneration = 1L,
            probeInFlight = true,
            updatedAt = DataLoomInstant(2L),
            probeLeaseUntil = DataLoomInstant(3L),
        )
        assertEquals(DataLoomInstant(3L), halfOpen.probeLeaseUntil)
        assertFailsWith<IllegalArgumentException> {
            halfOpen.copy(probeLeaseUntil = DataLoomInstant(2L))
        }
    }

    @Test
    fun `compare and set request requires matching scope`() {
        val scope = CircuitBreakerScope.provider(providerId)
        val other = CircuitBreakerScope.workflow(WorkflowId("workflow-002"))
        val state = CircuitBreakerState(
            scope = other,
            phase = CircuitBreakerPhase.CLOSED,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = null,
            probeGeneration = 0L,
            probeInFlight = false,
            updatedAt = DataLoomInstant(2L),
        )
        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerCompareAndSetRequest(
                scope = scope,
                expectedVersion = null,
                nextState = state,
            )
        }
    }
}
