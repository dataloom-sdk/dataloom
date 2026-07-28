package io.dataloom.api.provider

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConfigurationVersion
import io.dataloom.api.identifier.RuntimeVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ProviderContractsTest {

    @Test
    fun `provider type exposes all required values`() {
        assertEquals(
            setOf(
                "STORAGE",
                "TRANSPORT",
                "SCHEDULER",
                "CONNECTIVITY",
                "AUTHENTICATION",
                "SERIALIZATION",
                "ENCRYPTION",
                "COMPRESSION",
                "LOGGING",
                "MONITORING",
                "QUEUE",
            ),
            ProviderType.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `provider descriptor preserves required values`() {
        val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("provider.storage"),
            name = ProviderName("Storage Provider"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        assertEquals("provider.storage", descriptor.id.value)
        assertEquals("Storage Provider", descriptor.name.value)
        assertEquals(ProviderType.STORAGE, descriptor.type)
        assertEquals("1.0.0", descriptor.version.value)
    }

    @Test
    fun `provider descriptor supports empty capabilities and defaults metadata`() {
        val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("provider.transport"),
            name = ProviderName("Transport Provider"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        assertEquals(emptySet(), descriptor.capabilities)
        assertEquals(DataLoomMetadata.Empty, descriptor.metadata)
    }

    @Test
    fun `provider descriptor defensively copies capabilities`() {
        val source: MutableSet<ProviderCapability> = mutableSetOf(ProviderCapability("batch-read"))
        val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("provider.capability"),
            name = ProviderName("Capability Provider"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
            capabilities = source,
        )

        source += ProviderCapability("new-capability")

        assertEquals(setOf(ProviderCapability("batch-read")), descriptor.capabilities)
    }

    @Test
    fun `equal provider descriptors compare as equal`() {
        val first: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("provider.eq"),
            name = ProviderName("Equal Provider"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
            capabilities = setOf(ProviderCapability("feature-a")),
            metadata = DataLoomMetadata.of(mapOf("scope" to "test")),
        )
        val second: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("provider.eq"),
            name = ProviderName("Equal Provider"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
            capabilities = setOf(ProviderCapability("feature-a")),
            metadata = DataLoomMetadata.of(mapOf("scope" to "test")),
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `different provider descriptors compare as unequal`() {
        val first: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("provider.one"),
            name = ProviderName("Provider One"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )
        val second: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("provider.two"),
            name = ProviderName("Provider Two"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `provider lifecycle state exposes all required values`() {
        assertEquals(
            setOf("CREATED", "INITIALIZING", "READY", "DEGRADED", "FAILED", "CLOSING", "CLOSED"),
            ProviderLifecycleState.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `provider health status exposes all required values`() {
        assertEquals(
            setOf("UNKNOWN", "HEALTHY", "DEGRADED", "UNHEALTHY"),
            ProviderHealthStatus.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `provider health preserves status and default details`() {
        val health: ProviderHealth = ProviderHealth(
            status = ProviderHealthStatus.HEALTHY,
        )

        assertEquals(ProviderHealthStatus.HEALTHY, health.status)
        assertNull(health.error)
        assertEquals(DataLoomMetadata.Empty, health.details)
    }

    @Test
    fun `provider health may include an error`() {
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("DL-PROVIDER-001"),
            category = ErrorCategory.PROVIDER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "Provider degraded.",
            cause = null,
        )
        val health: ProviderHealth = ProviderHealth(
            status = ProviderHealthStatus.UNHEALTHY,
            error = error,
            details = DataLoomMetadata.of(mapOf("reason" to "dependency unavailable")),
        )

        assertEquals(error, health.error)
        assertEquals("dependency unavailable", health.details["reason"])
    }

    @Test
    fun `equal provider health values compare as equal`() {
        val first: ProviderHealth = ProviderHealth(
            status = ProviderHealthStatus.DEGRADED,
            details = DataLoomMetadata.of(mapOf("path" to "fallback")),
        )
        val second: ProviderHealth = ProviderHealth(
            status = ProviderHealthStatus.DEGRADED,
            details = DataLoomMetadata.of(mapOf("path" to "fallback")),
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `initialization context optional versions may be absent`() {
        val context: ProviderInitializationContext = ProviderInitializationContext()

        assertNull(context.runtimeVersion)
        assertNull(context.configurationVersion)
        assertEquals(DataLoomMetadata.Empty, context.metadata)
    }

    @Test
    fun `initialization context preserves supplied versions`() {
        val context: ProviderInitializationContext = ProviderInitializationContext(
            runtimeVersion = RuntimeVersion("runtime-1.0.0"),
            configurationVersion = ConfigurationVersion("config-1.0.0"),
        )

        assertEquals("runtime-1.0.0", context.runtimeVersion?.value)
        assertEquals("config-1.0.0", context.configurationVersion?.value)
    }

    @Test
    fun `initialization context preserves supplied metadata immutably`() {
        val source: MutableMap<String, String> = mutableMapOf("channel" to "manual")
        val metadata: DataLoomMetadata = DataLoomMetadata.of(source)
        val context: ProviderInitializationContext = ProviderInitializationContext(
            metadata = metadata,
        )

        source["channel"] = "mutated"

        assertEquals("manual", context.metadata["channel"])
    }

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
