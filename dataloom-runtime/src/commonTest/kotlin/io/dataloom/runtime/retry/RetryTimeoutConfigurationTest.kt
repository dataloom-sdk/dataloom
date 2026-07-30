package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RetryTimeoutConfigurationTest {
    @Test
    fun `each timeout boundary remains independent`() {
        val configuration = RetryTimeoutConfiguration(
            connectionTimeout = SchedulingDelay(1_000L),
            requestTimeout = SchedulingDelay(2_000L),
            idleTimeout = SchedulingDelay(3_000L),
            providerTimeout = SchedulingDelay(4_000L),
            policyTimeout = SchedulingDelay(5_000L),
            workflowTimeout = SchedulingDelay(6_000L),
        )

        assertEquals(SchedulingDelay(1_000L), configuration.timeoutFor(RetryTimeoutKind.CONNECTION))
        assertEquals(SchedulingDelay(2_000L), configuration.timeoutFor(RetryTimeoutKind.REQUEST))
        assertEquals(SchedulingDelay(3_000L), configuration.timeoutFor(RetryTimeoutKind.IDLE))
        assertEquals(SchedulingDelay(4_000L), configuration.timeoutFor(RetryTimeoutKind.PROVIDER))
        assertEquals(SchedulingDelay(5_000L), configuration.timeoutFor(RetryTimeoutKind.POLICY))
        assertEquals(SchedulingDelay(6_000L), configuration.timeoutFor(RetryTimeoutKind.WORKFLOW))
    }

    @Test
    fun `unconfigured boundaries remain null`() {
        val configuration = RetryTimeoutConfiguration(
            workflowTimeout = SchedulingDelay(30_000L),
        )

        assertNull(configuration.timeoutFor(RetryTimeoutKind.CONNECTION))
        assertNull(configuration.timeoutFor(RetryTimeoutKind.REQUEST))
        assertNull(configuration.timeoutFor(RetryTimeoutKind.IDLE))
        assertNull(configuration.timeoutFor(RetryTimeoutKind.PROVIDER))
        assertNull(configuration.timeoutFor(RetryTimeoutKind.POLICY))
        assertEquals(SchedulingDelay(30_000L), configuration.timeoutFor(RetryTimeoutKind.WORKFLOW))
    }

    @Test
    fun `configuration rejects an entirely empty timeout set`() {
        assertFailsWith<IllegalArgumentException> {
            RetryTimeoutConfiguration()
        }
    }

    @Test
    fun `timeout kind names are stable and distinct`() {
        assertEquals(
            setOf("CONNECTION", "REQUEST", "IDLE", "PROVIDER", "POLICY", "WORKFLOW"),
            RetryTimeoutKind.entries.map { it.name }.toSet(),
        )
    }
}
