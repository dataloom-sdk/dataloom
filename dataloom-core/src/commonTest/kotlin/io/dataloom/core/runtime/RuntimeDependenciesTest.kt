package io.dataloom.core.runtime

import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies the [RuntimeDependencies] contract.
 *
 * Uses private deterministic test stubs. No real clock access, real
 * identifier generation, randomness, or platform dependency is required.
 */
class RuntimeDependenciesTest {

    // -------------------------------------------------------------------------
    // Private test stubs
    // -------------------------------------------------------------------------

    private class TrackingClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        var callCount = 0
        override fun now(): DataLoomInstant {
            callCount++
            return instant
        }
    }

    private class TrackingGenerator<T>(private val value: T) : IdentifierGenerator<T> {
        var callCount = 0
        override fun generate(): T {
            callCount++
            return value
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val trackingClock = TrackingClock(DataLoomInstant(epochMilliseconds = 1_000L))

    private val eventGen = TrackingGenerator(SynchronizationEventId("e-001"))
    private val entryGen = TrackingGenerator(QueueEntryId("q-001"))
    private val leaseGen = TrackingGenerator(QueueLeaseId("l-001"))
    private val conflictGen = TrackingGenerator(ConflictId("c-001"))

    private fun buildIdentifiers(): RuntimeIdentifierGenerators =
        RuntimeIdentifierGenerators(
            synchronizationEventIds = eventGen,
            queueEntryIds = entryGen,
            queueLeaseIds = leaseGen,
            conflictIds = conflictGen,
        )

    private fun buildDependencies(): RuntimeDependencies =
        RuntimeDependencies(
            clock = trackingClock,
            identifiers = buildIdentifiers(),
        )

    // -------------------------------------------------------------------------
    // Dependency preservation
    // -------------------------------------------------------------------------

    @Test
    fun `clock is preserved`() {
        val deps = buildDependencies()
        assertSame(trackingClock, deps.clock)
    }

    @Test
    fun `identifiers is preserved`() {
        val identifiers = buildIdentifiers()
        val deps = RuntimeDependencies(
            clock = trackingClock,
            identifiers = identifiers,
        )
        assertSame(identifiers, deps.identifiers)
    }

    // -------------------------------------------------------------------------
    // Construction does not access dependencies
    // -------------------------------------------------------------------------

    @Test
    fun `construction does not read the clock`() {
        val clock = TrackingClock(DataLoomInstant(epochMilliseconds = 0L))

        RuntimeDependencies(
            clock = clock,
            identifiers = buildIdentifiers(),
        )

        assertEquals(0, clock.callCount, "clock.now() must not be called during construction")
    }

    @Test
    fun `construction does not invoke any generator`() {
        val eventGen2 = TrackingGenerator(SynchronizationEventId("x"))
        val entryGen2 = TrackingGenerator(QueueEntryId("x"))
        val leaseGen2 = TrackingGenerator(QueueLeaseId("x"))
        val conflictGen2 = TrackingGenerator(ConflictId("x"))

        RuntimeDependencies(
            clock = trackingClock,
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds = eventGen2,
                queueEntryIds = entryGen2,
                queueLeaseIds = leaseGen2,
                conflictIds = conflictGen2,
            ),
        )

        assertEquals(0, eventGen2.callCount, "synchronizationEventIds must not be called during construction")
        assertEquals(0, entryGen2.callCount, "queueEntryIds must not be called during construction")
        assertEquals(0, leaseGen2.callCount, "queueLeaseIds must not be called during construction")
        assertEquals(0, conflictGen2.callCount, "conflictIds must not be called during construction")
    }

    // -------------------------------------------------------------------------
    // No global dependency access
    // -------------------------------------------------------------------------

    @Test
    fun `clock can be accessed through the container`() {
        val deps = buildDependencies()
        assertEquals(DataLoomInstant(1_000L), deps.clock.now())
    }

    @Test
    fun `identifier generator can be accessed through the container`() {
        val deps = buildDependencies()
        assertEquals(SynchronizationEventId("e-001"), deps.identifiers.synchronizationEventIds.generate())
    }
}
