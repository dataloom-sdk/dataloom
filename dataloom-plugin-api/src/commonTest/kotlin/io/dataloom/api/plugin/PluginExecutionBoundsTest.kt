package io.dataloom.api.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginExecutionBoundsTest {

    @Test
    fun `accepts positive bounds`() {
        val bounds = PluginExecutionBounds(
            maximumExecutionMillis = 5_000L,
            maximumConcurrentInvocations = 4,
        )

        assertEquals(5_000L, bounds.maximumExecutionMillis)
        assertEquals(4, bounds.maximumConcurrentInvocations)
    }

    @Test
    fun `rejects zero or negative maximumExecutionMillis`() {
        assertFailsWith<IllegalArgumentException> {
            PluginExecutionBounds(maximumExecutionMillis = 0L, maximumConcurrentInvocations = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            PluginExecutionBounds(maximumExecutionMillis = -1L, maximumConcurrentInvocations = 1)
        }
    }

    @Test
    fun `rejects zero or negative maximumConcurrentInvocations`() {
        assertFailsWith<IllegalArgumentException> {
            PluginExecutionBounds(maximumExecutionMillis = 1_000L, maximumConcurrentInvocations = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PluginExecutionBounds(maximumExecutionMillis = 1_000L, maximumConcurrentInvocations = -1)
        }
    }
}
