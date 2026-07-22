package io.dataloom.api.identifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies the [IdentifierGenerator] interface contract using private test
 * implementations.
 *
 * No UUID, randomness, platform-specific type, or asynchronous API is required.
 */
class IdentifierGeneratorTest {

    // -------------------------------------------------------------------------
    // Private strongly typed identifiers for structural tests
    // -------------------------------------------------------------------------

    @JvmInline
    private value class TestId(val value: String)

    @JvmInline
    private value class OtherId(val value: String)

    // -------------------------------------------------------------------------
    // Private test implementations
    // -------------------------------------------------------------------------

    private class ConstantTestIdGenerator(
        private val id: TestId,
    ) : IdentifierGenerator<TestId> {
        override fun generate(): TestId = id
    }

    private class ConstantOtherIdGenerator(
        private val id: OtherId,
    ) : IdentifierGenerator<OtherId> {
        override fun generate(): OtherId = id
    }

    // -------------------------------------------------------------------------
    // Contract verification
    // -------------------------------------------------------------------------

    @Test
    fun `generate returns the strongly typed identifier`() {
        val expected = TestId("test-id-001")
        val generator = ConstantTestIdGenerator(expected)

        assertEquals(expected, generator.generate())
    }

    @Test
    fun `generate returns the correct type for a different identifier type`() {
        val expected = OtherId("other-id-001")
        val generator = ConstantOtherIdGenerator(expected)

        assertEquals(expected, generator.generate())
    }

    @Test
    fun `different generator instances can produce different identifier types`() {
        val testGenerator: IdentifierGenerator<TestId> =
            ConstantTestIdGenerator(TestId("a"))
        val otherGenerator: IdentifierGenerator<OtherId> =
            ConstantOtherIdGenerator(OtherId("b"))

        // Structural verification: both satisfy IdentifierGenerator without a
        // shared base type for the produced identifiers.
        assertEquals("a", testGenerator.generate().value)
        assertEquals("b", otherGenerator.generate().value)
    }

    @Test
    fun `no raw string API is required to implement IdentifierGenerator`() {
        // Structural test: the interface is satisfied by a pure Kotlin class
        // producing a strongly typed value, not a raw String.
        val generator: IdentifierGenerator<TestId> =
            ConstantTestIdGenerator(TestId("structural-test-id"))
        assertEquals(TestId("structural-test-id"), generator.generate())
    }

    @Test
    fun `no platform-specific type is required to implement IdentifierGenerator`() {
        // Structural test: the interface is satisfied by a pure Kotlin class
        // with no java.util.UUID, java.time, or Android dependency.
        val generator: IdentifierGenerator<TestId> =
            ConstantTestIdGenerator(TestId("platform-independent-id"))
        assertEquals("platform-independent-id", generator.generate().value)
    }

    @Test
    fun `repeated calls return equal values for a constant implementation`() {
        val generator = ConstantTestIdGenerator(TestId("repeat-id"))

        val first = generator.generate()
        val second = generator.generate()

        assertEquals(first, second)
    }

    @Test
    fun `different generators for the same type are independent`() {
        val generatorA = ConstantTestIdGenerator(TestId("id-a"))
        val generatorB = ConstantTestIdGenerator(TestId("id-b"))

        assertNotEquals(generatorA.generate(), generatorB.generate())
    }
}
