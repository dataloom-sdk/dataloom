package io.dataloom.api.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelEnumContractsTest {

    @Test
    fun `workflow lifecycle state exposes all required values`() {
        assertEquals(
            setOf(
                "CREATED",
                "VALIDATED",
                "QUEUED",
                "SCHEDULED",
                "RUNNING",
                "SUCCEEDED",
                "FAILED",
                "CANCELLED",
            ),
            WorkflowLifecycleState.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `synchronization direction exposes all required values`() {
        assertEquals(
            setOf("PUSH", "PULL", "BIDIRECTIONAL"),
            SynchronizationDirection.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `synchronization mode exposes all required values`() {
        assertEquals(
            setOf("FULL", "DELTA"),
            SynchronizationMode.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `change operation exposes all required values`() {
        assertEquals(
            setOf("CREATE", "UPDATE", "DELETE", "MERGE", "RESTORE"),
            ChangeOperation.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `workflow priority exposes all required values`() {
        assertEquals(
            setOf("LOW", "NORMAL", "HIGH", "CRITICAL"),
            WorkflowPriority.entries.map { it.name }.toSet(),
        )
    }
}
