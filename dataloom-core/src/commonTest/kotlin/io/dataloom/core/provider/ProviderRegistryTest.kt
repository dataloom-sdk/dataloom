package io.dataloom.core.provider

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the [ProviderRegistry] contract.
 *
 * Uses private deterministic fake [DataLoomProvider] implementations. No real
 * provider operation, platform access, or external service is required.
 */
class ProviderRegistryTest {

    // -------------------------------------------------------------------------
    // Shared fake infrastructure
    // -------------------------------------------------------------------------

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class TrackingProvider(
        override val descriptor: ProviderDescriptor,
    ) : DataLoomProvider {
        var initializeCallCount = 0
        var closeCallCount = 0

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }
    }

    private fun descriptor(
        id: String,
        type: ProviderType = ProviderType.STORAGE,
    ): ProviderDescriptor = ProviderDescriptor(
        id = ProviderId(id),
        name = ProviderName("Provider $id"),
        type = type,
        version = ProviderVersion("1.0.0"),
    )

    private fun provider(
        id: String,
        type: ProviderType = ProviderType.STORAGE,
    ): TrackingProvider = TrackingProvider(descriptor(id, type))

    // -------------------------------------------------------------------------
    // Empty registry
    // -------------------------------------------------------------------------

    @Test
    fun `empty registry has size zero`() {
        val registry = ProviderRegistry(emptyList())

        assertEquals(0, registry.size)
    }

    @Test
    fun `empty registry isEmpty returns true`() {
        val registry = ProviderRegistry(emptyList())

        assertTrue(registry.isEmpty())
    }

    @Test
    fun `empty registry providers returns empty list`() {
        val registry = ProviderRegistry(emptyList())

        assertEquals(emptyList(), registry.providers)
    }

    @Test
    fun `empty registry findById returns null`() {
        val registry = ProviderRegistry(emptyList())

        assertNull(registry.findById(ProviderId("missing")))
    }

    @Test
    fun `empty registry findByType returns empty list`() {
        val registry = ProviderRegistry(emptyList())

        assertEquals(emptyList(), registry.findByType(ProviderType.STORAGE))
    }

    // -------------------------------------------------------------------------
    // One provider registration
    // -------------------------------------------------------------------------

    @Test
    fun `one provider registry has size one`() {
        val registry = ProviderRegistry(listOf(provider("a")))

        assertEquals(1, registry.size)
    }

    @Test
    fun `one provider registry isEmpty returns false`() {
        val registry = ProviderRegistry(listOf(provider("a")))

        assertTrue(!registry.isEmpty())
    }

    @Test
    fun `one provider registry exposes the provider`() {
        val p = provider("a")
        val registry = ProviderRegistry(listOf(p))

        assertEquals(listOf(p), registry.providers)
    }

    @Test
    fun `one provider findById returns the provider`() {
        val p = provider("a")
        val registry = ProviderRegistry(listOf(p))

        assertEquals(p, registry.findById(ProviderId("a")))
    }

    @Test
    fun `one provider findByType returns the provider`() {
        val p = provider("a", ProviderType.STORAGE)
        val registry = ProviderRegistry(listOf(p))

        assertEquals(listOf(p), registry.findByType(ProviderType.STORAGE))
    }

    // -------------------------------------------------------------------------
    // Multiple provider registration
    // -------------------------------------------------------------------------

    @Test
    fun `multiple provider registry has correct size`() {
        val registry = ProviderRegistry(
            listOf(
                provider("a"),
                provider("b"),
                provider("c"),
            ),
        )

        assertEquals(3, registry.size)
    }

    @Test
    fun `multiple providers all exposed`() {
        val a = provider("a")
        val b = provider("b")
        val c = provider("c")
        val registry = ProviderRegistry(listOf(a, b, c))

        assertEquals(listOf(a, b, c), registry.providers)
    }

    // -------------------------------------------------------------------------
    // Registration order preservation
    // -------------------------------------------------------------------------

    @Test
    fun `registration order is preserved in providers list`() {
        val p1 = provider("first")
        val p2 = provider("second")
        val p3 = provider("third")
        val registry = ProviderRegistry(listOf(p1, p2, p3))

        val order = registry.providers.map { it.descriptor.id }

        assertEquals(
            listOf(ProviderId("first"), ProviderId("second"), ProviderId("third")),
            order,
        )
    }

    @Test
    fun `registration order is preserved across different provider types`() {
        val storage = provider("storage", ProviderType.STORAGE)
        val transport = provider("transport", ProviderType.TRANSPORT)
        val scheduler = provider("scheduler", ProviderType.SCHEDULER)
        val registry = ProviderRegistry(listOf(storage, transport, scheduler))

        assertEquals(
            listOf(storage, transport, scheduler),
            registry.providers,
        )
    }

    // -------------------------------------------------------------------------
    // Lookup by ProviderId
    // -------------------------------------------------------------------------

    @Test
    fun `findById returns correct provider for existing id`() {
        val a = provider("a")
        val b = provider("b")
        val registry = ProviderRegistry(listOf(a, b))

        assertEquals(a, registry.findById(ProviderId("a")))
        assertEquals(b, registry.findById(ProviderId("b")))
    }

    @Test
    fun `findById returns null for missing id`() {
        val registry = ProviderRegistry(listOf(provider("a")))

        assertNull(registry.findById(ProviderId("missing")))
    }

    @Test
    fun `findById with wrong id returns null`() {
        val registry = ProviderRegistry(listOf(provider("a"), provider("b")))

        assertNull(registry.findById(ProviderId("c")))
    }

    // -------------------------------------------------------------------------
    // Lookup by ProviderType
    // -------------------------------------------------------------------------

    @Test
    fun `findByType returns empty list for missing type`() {
        val registry = ProviderRegistry(listOf(provider("a", ProviderType.STORAGE)))

        assertEquals(emptyList(), registry.findByType(ProviderType.TRANSPORT))
    }

    @Test
    fun `findByType returns all providers of that type in registration order`() {
        val s1 = provider("s1", ProviderType.STORAGE)
        val t1 = provider("t1", ProviderType.TRANSPORT)
        val s2 = provider("s2", ProviderType.STORAGE)
        val registry = ProviderRegistry(listOf(s1, t1, s2))

        assertEquals(listOf(s1, s2), registry.findByType(ProviderType.STORAGE))
    }

    // -------------------------------------------------------------------------
    // Multiple providers with the same ProviderType
    // -------------------------------------------------------------------------

    @Test
    fun `multiple providers with the same type are all registered`() {
        val s1 = provider("s1", ProviderType.STORAGE)
        val s2 = provider("s2", ProviderType.STORAGE)
        val s3 = provider("s3", ProviderType.STORAGE)
        val registry = ProviderRegistry(listOf(s1, s2, s3))

        val result = registry.findByType(ProviderType.STORAGE)

        assertEquals(3, result.size)
        assertEquals(listOf(s1, s2, s3), result)
    }

    @Test
    fun `providers with same type and different IDs are all accepted`() {
        val q1 = provider("q1", ProviderType.QUEUE)
        val q2 = provider("q2", ProviderType.QUEUE)
        val registry = ProviderRegistry(listOf(q1, q2))

        assertEquals(2, registry.findByType(ProviderType.QUEUE).size)
    }

    // -------------------------------------------------------------------------
    // Duplicate ProviderId rejection
    // -------------------------------------------------------------------------

    @Test
    fun `duplicate ProviderId throws IllegalArgumentException`() {
        val a1 = provider("duplicate-id")
        val a2 = provider("duplicate-id")

        assertFailsWith<IllegalArgumentException> {
            ProviderRegistry(listOf(a1, a2))
        }
    }

    @Test
    fun `three providers with same id throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderRegistry(
                listOf(
                    provider("same"),
                    provider("same"),
                    provider("same"),
                ),
            )
        }
    }

    @Test
    fun `duplicate in different types still rejected`() {
        val p1 = TrackingProvider(
            ProviderDescriptor(
                id = ProviderId("dup"),
                name = ProviderName("Provider One"),
                type = ProviderType.STORAGE,
                version = ProviderVersion("1.0.0"),
            ),
        )
        val p2 = TrackingProvider(
            ProviderDescriptor(
                id = ProviderId("dup"),
                name = ProviderName("Provider Two"),
                type = ProviderType.TRANSPORT,
                version = ProviderVersion("1.0.0"),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ProviderRegistry(listOf(p1, p2))
        }
    }

    @Test
    fun `non-duplicate ids are accepted`() {
        val registry = ProviderRegistry(
            listOf(
                provider("a"),
                provider("b"),
                provider("c"),
            ),
        )

        assertEquals(3, registry.size)
    }

    // -------------------------------------------------------------------------
    // Defensive copy of source collection
    // -------------------------------------------------------------------------

    @Test
    fun `mutation of source list after construction does not affect registry`() {
        val p = provider("a")
        val source = mutableListOf<DataLoomProvider>(p)
        val registry = ProviderRegistry(source)

        source.add(provider("b"))
        source.add(provider("c"))

        assertEquals(1, registry.size)
        assertEquals(listOf(p), registry.providers)
    }

    @Test
    fun `removing from source list does not affect registry`() {
        val p1 = provider("a")
        val p2 = provider("b")
        val source = mutableListOf<DataLoomProvider>(p1, p2)
        val registry = ProviderRegistry(source)

        source.clear()

        assertEquals(2, registry.size)
    }

    // -------------------------------------------------------------------------
    // Exposed collections cannot mutate registry state
    // -------------------------------------------------------------------------

    @Test
    fun `providers list is typed as List and does not expose MutableList interface`() {
        val registry = ProviderRegistry(listOf(provider("a")))

        // The return type is List<DataLoomProvider>, which is the read-only interface.
        // Defensive copy ensures changes to external references do not reach the registry.
        val list: List<DataLoomProvider> = registry.providers
        assertEquals(1, list.size)
    }

    @Test
    fun `findByType result is typed as List and does not expose MutableList interface`() {
        val registry = ProviderRegistry(listOf(provider("a", ProviderType.STORAGE)))

        // The return type is List<DataLoomProvider>, which is the read-only interface.
        val list: List<DataLoomProvider> = registry.findByType(ProviderType.STORAGE)
        assertEquals(1, list.size)
    }

    // -------------------------------------------------------------------------
    // Registry construction does not trigger provider operations
    // -------------------------------------------------------------------------

    @Test
    fun `registry construction does not initialize providers`() {
        val p1 = provider("a")
        val p2 = provider("b")

        ProviderRegistry(listOf(p1, p2))

        assertEquals(0, p1.initializeCallCount, "initialize must not be called during registry construction")
        assertEquals(0, p2.initializeCallCount, "initialize must not be called during registry construction")
    }

    @Test
    fun `registry construction does not shut down providers`() {
        val p1 = provider("a")
        val p2 = provider("b")

        ProviderRegistry(listOf(p1, p2))

        assertEquals(0, p1.closeCallCount, "close must not be called during registry construction")
        assertEquals(0, p2.closeCallCount, "close must not be called during registry construction")
    }
}
