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
    fun `execution id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::ExecutionId,
            extract = ExecutionId::value,
            valid = "execution-001",
            different = "execution-002",
        )
    }

    @Test
    fun `request id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::RequestId,
            extract = RequestId::value,
            valid = "request-001",
            different = "request-002",
        )
    }

    @Test
    fun `tenant id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::TenantId,
            extract = TenantId::value,
            valid = "tenant-001",
            different = "tenant-002",
        )
    }

    @Test
    fun `user id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::UserId,
            extract = UserId::value,
            valid = "user-001",
            different = "user-002",
        )
    }

    @Test
    fun `runtime version satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::RuntimeVersion,
            extract = RuntimeVersion::value,
            valid = "runtime-1.0.0",
            different = "runtime-1.1.0",
        )
    }

    @Test
    fun `configuration version satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::ConfigurationVersion,
            extract = ConfigurationVersion::value,
            valid = "config-2026-07-21",
            different = "config-2026-07-22",
        )
    }

    @Test
    fun `locale tag satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::LocaleTag,
            extract = LocaleTag::value,
            valid = "en-US",
            different = "fr-FR",
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

    @Test
    fun `conflict id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::ConflictId,
            extract = ConflictId::value,
            valid = "conflict-001",
            different = "conflict-002",
        )
    }

    @Test
    fun `conflict detector id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::ConflictDetectorId,
            extract = ConflictDetectorId::value,
            valid = "entity-version-detector",
            different = "application-order-detector",
        )
    }

    @Test
    fun `conflict resolver id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::ConflictResolverId,
            extract = ConflictResolverId::value,
            valid = "client-preferred-resolver",
            different = "server-preferred-resolver",
        )
    }

    @Test
    fun `checkpoint key satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::CheckpointKey,
            extract = CheckpointKey::value,
            valid = "customers-pull",
            different = "orders-tenant-example",
        )
    }

    @Test
    fun `checkpoint token satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::CheckpointToken,
            extract = CheckpointToken::value,
            valid = "token-001",
            different = "token-002",
        )
    }

    @Test
    fun `queue consumer id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::QueueConsumerId,
            extract = QueueConsumerId::value,
            valid = "consumer-worker-001",
            different = "consumer-worker-002",
        )
    }

    @Test
    fun `synchronization event id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::SynchronizationEventId,
            extract = SynchronizationEventId::value,
            valid = "sync-event-001",
            different = "sync-event-002",
        )
    }

    @Test
    fun `synchronization observer id satisfies canonical identifier behavior`() {
        assertIdentifierBehavior(
            create = ::SynchronizationObserverId,
            extract = SynchronizationObserverId::value,
            valid = "analytics-observer",
            different = "debug-observer-example",
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
