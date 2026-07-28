package io.dataloom.api.runtime

import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies the [RuntimeIdentifierGenerators] contract.
 *
 * Uses private deterministic test generators. No real identifier generation,
 * randomness, or platform dependency is required.
 */
class RuntimeIdentifierGeneratorsTest {

    // -------------------------------------------------------------------------
    // Private constant generators for testing
    // -------------------------------------------------------------------------

    private class ConstantSynchronizationEventIdGenerator(
        private val id: SynchronizationEventId,
    ) : IdentifierGenerator<SynchronizationEventId> {
        var callCount = 0
        override fun generate(): SynchronizationEventId {
            callCount++
            return id
        }
    }

    private class ConstantQueueEntryIdGenerator(
        private val id: QueueEntryId,
    ) : IdentifierGenerator<QueueEntryId> {
        var callCount = 0
        override fun generate(): QueueEntryId {
            callCount++
            return id
        }
    }

    private class ConstantQueueLeaseIdGenerator(
        private val id: QueueLeaseId,
    ) : IdentifierGenerator<QueueLeaseId> {
        var callCount = 0
        override fun generate(): QueueLeaseId {
            callCount++
            return id
        }
    }

    private class ConstantConflictIdGenerator(
        private val id: ConflictId,
    ) : IdentifierGenerator<ConflictId> {
        var callCount = 0
        override fun generate(): ConflictId {
            callCount++
            return id
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val eventGenerator = ConstantSynchronizationEventIdGenerator(
        SynchronizationEventId("event-001"),
    )
    private val entryGenerator = ConstantQueueEntryIdGenerator(
        QueueEntryId("entry-001"),
    )
    private val leaseGenerator = ConstantQueueLeaseIdGenerator(
        QueueLeaseId("lease-001"),
    )
    private val conflictGenerator = ConstantConflictIdGenerator(
        ConflictId("conflict-001"),
    )

    private fun buildGenerators(): RuntimeIdentifierGenerators =
        RuntimeIdentifierGenerators(
            synchronizationEventIds = eventGenerator,
            queueEntryIds = entryGenerator,
            queueLeaseIds = leaseGenerator,
            conflictIds = conflictGenerator,
        )

    // -------------------------------------------------------------------------
    // Generator preservation
    // -------------------------------------------------------------------------

    @Test
    fun `synchronizationEventIds generator is preserved`() {
        val generators = buildGenerators()
        assertSame(eventGenerator, generators.synchronizationEventIds)
    }

    @Test
    fun `queueEntryIds generator is preserved`() {
        val generators = buildGenerators()
        assertSame(entryGenerator, generators.queueEntryIds)
    }

    @Test
    fun `queueLeaseIds generator is preserved`() {
        val generators = buildGenerators()
        assertSame(leaseGenerator, generators.queueLeaseIds)
    }

    @Test
    fun `conflictIds generator is preserved`() {
        val generators = buildGenerators()
        assertSame(conflictGenerator, generators.conflictIds)
    }

    // -------------------------------------------------------------------------
    // No generation during construction
    // -------------------------------------------------------------------------

    @Test
    fun `construction does not invoke any generator`() {
        val eventGen = ConstantSynchronizationEventIdGenerator(SynchronizationEventId("e"))
        val entryGen = ConstantQueueEntryIdGenerator(QueueEntryId("q"))
        val leaseGen = ConstantQueueLeaseIdGenerator(QueueLeaseId("l"))
        val conflictGen = ConstantConflictIdGenerator(ConflictId("c"))

        RuntimeIdentifierGenerators(
            synchronizationEventIds = eventGen,
            queueEntryIds = entryGen,
            queueLeaseIds = leaseGen,
            conflictIds = conflictGen,
        )

        assertEquals(0, eventGen.callCount, "synchronizationEventIds must not be called during construction")
        assertEquals(0, entryGen.callCount, "queueEntryIds must not be called during construction")
        assertEquals(0, leaseGen.callCount, "queueLeaseIds must not be called during construction")
        assertEquals(0, conflictGen.callCount, "conflictIds must not be called during construction")
    }

    // -------------------------------------------------------------------------
    // Generators are only called when explicitly requested
    // -------------------------------------------------------------------------

    @Test
    fun `synchronizationEventIds generator is only called on explicit request`() {
        val generators = buildGenerators()
        assertEquals(0, eventGenerator.callCount)

        generators.synchronizationEventIds.generate()

        assertEquals(1, eventGenerator.callCount)
    }

    @Test
    fun `queueEntryIds generator is only called on explicit request`() {
        val generators = buildGenerators()
        assertEquals(0, entryGenerator.callCount)

        generators.queueEntryIds.generate()

        assertEquals(1, entryGenerator.callCount)
    }

    @Test
    fun `queueLeaseIds generator is only called on explicit request`() {
        val generators = buildGenerators()
        assertEquals(0, leaseGenerator.callCount)

        generators.queueLeaseIds.generate()

        assertEquals(1, leaseGenerator.callCount)
    }

    @Test
    fun `conflictIds generator is only called on explicit request`() {
        val generators = buildGenerators()
        assertEquals(0, conflictGenerator.callCount)

        generators.conflictIds.generate()

        assertEquals(1, conflictGenerator.callCount)
    }

    // -------------------------------------------------------------------------
    // Generators produce the correct strongly typed values
    // -------------------------------------------------------------------------

    @Test
    fun `synchronizationEventIds generate returns SynchronizationEventId`() {
        val generators = buildGenerators()
        val id: SynchronizationEventId = generators.synchronizationEventIds.generate()
        assertEquals(SynchronizationEventId("event-001"), id)
    }

    @Test
    fun `queueEntryIds generate returns QueueEntryId`() {
        val generators = buildGenerators()
        val id: QueueEntryId = generators.queueEntryIds.generate()
        assertEquals(QueueEntryId("entry-001"), id)
    }

    @Test
    fun `queueLeaseIds generate returns QueueLeaseId`() {
        val generators = buildGenerators()
        val id: QueueLeaseId = generators.queueLeaseIds.generate()
        assertEquals(QueueLeaseId("lease-001"), id)
    }

    @Test
    fun `conflictIds generate returns ConflictId`() {
        val generators = buildGenerators()
        val id: ConflictId = generators.conflictIds.generate()
        assertEquals(ConflictId("conflict-001"), id)
    }
}
