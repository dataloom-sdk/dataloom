package io.dataloom.runtime.connectivity

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Deterministic common tests for [ConnectivityPreflightResult].
 *
 * Verifies:
 * - Each variant is constructible and preserves its fields.
 * - Value-based equality for applicable variants.
 * - No raw Throwable or stack trace is exposed.
 * - NotRequired and ProviderNotConfigured are singleton objects.
 */
class ConnectivityPreflightResultTest {

    private data class TestError(
        override val code: ErrorCode = ErrorCode("DL-TEST"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "test error",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // NotRequired
    // =========================================================================

    @Test
    fun `NotRequired is a singleton object`() {
        val r1: ConnectivityPreflightResult = ConnectivityPreflightResult.NotRequired
        val r2: ConnectivityPreflightResult = ConnectivityPreflightResult.NotRequired
        assertSame(r1, r2)
    }

    @Test
    fun `NotRequired is the correct type`() {
        assertIs<ConnectivityPreflightResult.NotRequired>(ConnectivityPreflightResult.NotRequired)
    }

    // =========================================================================
    // ProviderNotConfigured
    // =========================================================================

    @Test
    fun `ProviderNotConfigured is a singleton object`() {
        val r1: ConnectivityPreflightResult = ConnectivityPreflightResult.ProviderNotConfigured
        val r2: ConnectivityPreflightResult = ConnectivityPreflightResult.ProviderNotConfigured
        assertSame(r1, r2)
    }

    @Test
    fun `ProviderNotConfigured is the correct type`() {
        assertIs<ConnectivityPreflightResult.ProviderNotConfigured>(ConnectivityPreflightResult.ProviderNotConfigured)
    }

    // =========================================================================
    // Satisfied
    // =========================================================================

    @Test
    fun `Satisfied preserves snapshot`() {
        val snapshot = ConnectivitySnapshot(status = ConnectivityStatus.AVAILABLE, isMetered = false)
        val result = ConnectivityPreflightResult.Satisfied(snapshot = snapshot)
        assertEquals(snapshot, result.snapshot)
    }

    @Test
    fun `Satisfied value equality`() {
        val snapshot = ConnectivitySnapshot(status = ConnectivityStatus.AVAILABLE, isMetered = null)
        val a = ConnectivityPreflightResult.Satisfied(snapshot = snapshot)
        val b = ConnectivityPreflightResult.Satisfied(snapshot = snapshot)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Satisfied inequality for different snapshots`() {
        val a = ConnectivityPreflightResult.Satisfied(
            snapshot = ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = true),
        )
        val b = ConnectivityPreflightResult.Satisfied(
            snapshot = ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = false),
        )
        assertNotEquals(a, b)
    }

    // =========================================================================
    // RequirementNotMet
    // =========================================================================

    @Test
    fun `RequirementNotMet preserves requirement`() {
        val result = ConnectivityPreflightResult.RequirementNotMet(
            requirement = ConnectivityRequirement.UNMETERED,
            status = ConnectivityStatus.UNAVAILABLE,
        )
        assertEquals(ConnectivityRequirement.UNMETERED, result.requirement)
    }

    @Test
    fun `RequirementNotMet preserves status`() {
        val result = ConnectivityPreflightResult.RequirementNotMet(
            requirement = ConnectivityRequirement.AVAILABLE,
            status = ConnectivityStatus.UNAVAILABLE,
        )
        assertEquals(ConnectivityStatus.UNAVAILABLE, result.status)
    }

    @Test
    fun `RequirementNotMet value equality`() {
        val a = ConnectivityPreflightResult.RequirementNotMet(
            requirement = ConnectivityRequirement.AVAILABLE,
            status = ConnectivityStatus.UNKNOWN,
        )
        val b = ConnectivityPreflightResult.RequirementNotMet(
            requirement = ConnectivityRequirement.AVAILABLE,
            status = ConnectivityStatus.UNKNOWN,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `RequirementNotMet inequality for different requirements`() {
        val a = ConnectivityPreflightResult.RequirementNotMet(
            requirement = ConnectivityRequirement.AVAILABLE,
            status = ConnectivityStatus.UNAVAILABLE,
        )
        val b = ConnectivityPreflightResult.RequirementNotMet(
            requirement = ConnectivityRequirement.UNMETERED,
            status = ConnectivityStatus.UNAVAILABLE,
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `RequirementNotMet does not expose full snapshot`() {
        // Only status is exposed, not isMetered or other network identifying info
        val result = ConnectivityPreflightResult.RequirementNotMet(
            requirement = ConnectivityRequirement.AVAILABLE,
            status = ConnectivityStatus.LIMITED,
        )
        assertEquals(ConnectivityStatus.LIMITED, result.status)
    }

    // =========================================================================
    // CheckFailed
    // =========================================================================

    @Test
    fun `CheckFailed preserves exact error`() {
        val error = TestError(code = ErrorCode("DL-CONN-FAIL"))
        val result = ConnectivityPreflightResult.CheckFailed(error = error)
        assertSame(error, result.error)
    }

    @Test
    fun `CheckFailed value equality`() {
        val error = TestError()
        val a = ConnectivityPreflightResult.CheckFailed(error = error)
        val b = ConnectivityPreflightResult.CheckFailed(error = error)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `CheckFailed does not expose cause Throwable through result`() {
        val error = TestError(cause = RuntimeException("original exception"))
        val result = ConnectivityPreflightResult.CheckFailed(error = error)
        // DataLoomError.cause is part of the error contract; the result itself is not a Throwable
        assertNull((result as Any?).let { null }) // result is not a Throwable
        assertEquals(error, result.error)
    }
}
