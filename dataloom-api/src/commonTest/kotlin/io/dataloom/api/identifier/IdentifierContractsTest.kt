package io.dataloom.api.identifier

import io.dataloom.api.error.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

class IdentifierContractsTest {

    @Test
    fun `workflow id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::WorkflowId,
            extract = WorkflowId::value,
            valid = "workflow-001",
            different = "workflow-002",
        )
    }

    @Test
    fun `synchronization session id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::SynchronizationSessionId,
            extract = SynchronizationSessionId::value,
            valid = "session-001",
            different = "session-002",
        )
    }

    @Test
    fun `change event id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::ChangeEventId,
            extract = ChangeEventId::value,
            valid = "event-001",
            different = "event-002",
        )
    }

    @Test
    fun `change set id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::ChangeSetId,
            extract = ChangeSetId::value,
            valid = "changeset-001",
            different = "changeset-002",
        )
    }

    @Test
    fun `entity id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::EntityId,
            extract = EntityId::value,
            valid = "entity-001",
            different = "entity-002",
        )
    }

    @Test
    fun `entity type satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::EntityType,
            extract = EntityType::value,
            valid = "invoice",
            different = "payment",
        )
    }

    @Test
    fun `correlation id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::CorrelationId,
            extract = CorrelationId::value,
            valid = "corr-001",
            different = "corr-002",
        )
    }

    @Test
    fun `trace id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::TraceId,
            extract = TraceId::value,
            valid = "trace-001",
            different = "trace-002",
        )
    }

    @Test
    fun `error code satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::ErrorCode,
            extract = ErrorCode::value,
            valid = "DL-ERROR-001",
            different = "DL-ERROR-002",
        )
    }

    private fun <T> assertIdentifierBehavior(
        create: (String) -> T,
        extract: (T) -> String,
        valid: String,
        different: String,
    ) {
        val identifier: T = create(valid)
        val sameIdentifier: T = create(valid)
        val otherIdentifier: T = create(different)

        assertEquals(valid, extract(identifier))
        assertEquals(valid, identifier.toString())
        assertEquals(identifier, sameIdentifier)
        assertNotEquals(identifier, otherIdentifier)
        assertFailsWith<IllegalArgumentException> { create("") }
        assertFailsWith<IllegalArgumentException> { create("   ") }
    }
}
