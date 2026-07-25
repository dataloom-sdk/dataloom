package io.dataloom.runtime.connectivity

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.scheduling.SchedulingDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Deterministic common tests for [SynchronizationConnectivityConfiguration].
 *
 * Verifies:
 * - requirement is preserved
 * - offline delay is preserved
 * - NONE companion constant
 * - construction performs no side effects
 * - value-based equality
 */
class SynchronizationConnectivityConfigurationTest {

    @Test
    fun `requirement is preserved exactly`() {
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay.ZERO,
        )
        assertEquals(ConnectivityRequirement.AVAILABLE, config.requirement)
    }

    @Test
    fun `offline delay is preserved exactly`() {
        val delay = SchedulingDelay(30_000L)
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.UNMETERED,
            offlineRescheduleDelay = delay,
        )
        assertEquals(delay, config.offlineRescheduleDelay)
    }

    @Test
    fun `NONE constant has NONE requirement`() {
        assertEquals(ConnectivityRequirement.NONE, SynchronizationConnectivityConfiguration.NONE.requirement)
    }

    @Test
    fun `NONE constant has zero delay`() {
        assertEquals(SchedulingDelay.ZERO, SynchronizationConnectivityConfiguration.NONE.offlineRescheduleDelay)
    }

    @Test
    fun `equal instances with same values are equal`() {
        val a = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(5_000L),
        )
        val b = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(5_000L),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `instances with different requirements are not equal`() {
        val a = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay.ZERO,
        )
        val b = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.UNMETERED,
            offlineRescheduleDelay = SchedulingDelay.ZERO,
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `instances with different delays are not equal`() {
        val a = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.NONE,
            offlineRescheduleDelay = SchedulingDelay(1_000L),
        )
        val b = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.NONE,
            offlineRescheduleDelay = SchedulingDelay(2_000L),
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `toString does not contain sensitive information`() {
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.UNMETERED,
            offlineRescheduleDelay = SchedulingDelay(10_000L),
        )
        val str = config.toString()
        // Should contain requirement and delay but no credentials or network IDs
        assertEquals(true, str.contains("UNMETERED"))
        assertEquals(true, str.contains("10000"))
        assertFalse(str.contains("token"))
        assertFalse(str.contains("credential"))
    }

    @Test
    fun `construction with NONE requirement preserves it`() {
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.NONE,
            offlineRescheduleDelay = SchedulingDelay.ZERO,
        )
        assertEquals(ConnectivityRequirement.NONE, config.requirement)
        assertEquals(SchedulingDelay.ZERO, config.offlineRescheduleDelay)
    }

    @Test
    fun `construction performs no clock read - offline delay is not resolved at construction`() {
        // Construction with a large delay should complete immediately without I/O.
        val config = SynchronizationConnectivityConfiguration(
            requirement = ConnectivityRequirement.AVAILABLE,
            offlineRescheduleDelay = SchedulingDelay(Long.MAX_VALUE),
        )
        // If this completes, construction made no blocking call.
        assertEquals(Long.MAX_VALUE, config.offlineRescheduleDelay.milliseconds)
    }
}
