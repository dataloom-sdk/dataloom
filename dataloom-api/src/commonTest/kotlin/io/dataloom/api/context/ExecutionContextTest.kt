package io.dataloom.api.context

import io.dataloom.api.identifier.ConfigurationVersion
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.LocaleTag
import io.dataloom.api.identifier.RequestId
import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecutionContextTest {

    @Test
    fun `required identifiers are preserved`() {
        val context: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        )

        assertEquals("execution-001", context.executionId.value)
        assertEquals("corr-001", context.correlationId.value)
    }

    @Test
    fun `optional fields may be absent`() {
        val context: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        )

        assertNull(context.traceId)
        assertNull(context.requestId)
        assertNull(context.tenantId)
        assertNull(context.userId)
        assertNull(context.localeTag)
        assertNull(context.runtimeVersion)
        assertNull(context.configurationVersion)
    }

    @Test
    fun `optional fields are preserved when supplied`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("path" to "manual"))
        val context: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
            traceId = TraceId("trace-001"),
            requestId = RequestId("request-001"),
            tenantId = TenantId("tenant-001"),
            userId = UserId("user-001"),
            localeTag = LocaleTag("en-US"),
            runtimeVersion = RuntimeVersion("runtime-1.0.0"),
            configurationVersion = ConfigurationVersion("config-1.0.0"),
            metadata = metadata,
        )

        assertEquals("trace-001", context.traceId?.value)
        assertEquals("request-001", context.requestId?.value)
        assertEquals("tenant-001", context.tenantId?.value)
        assertEquals("user-001", context.userId?.value)
        assertEquals("en-US", context.localeTag?.value)
        assertEquals("runtime-1.0.0", context.runtimeVersion?.value)
        assertEquals("config-1.0.0", context.configurationVersion?.value)
        assertEquals("manual", context.metadata["path"])
    }

    @Test
    fun `metadata defaults to empty immutable value`() {
        val context: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        )

        assertEquals(DataLoomMetadata.Empty, context.metadata)
        assertEquals(true, context.metadata.isEmpty())
    }

    @Test
    fun `supplied metadata is preserved immutably`() {
        val source: MutableMap<String, String> = mutableMapOf("key" to "value")
        val metadata: DataLoomMetadata = DataLoomMetadata.of(source)

        val context: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
            metadata = metadata,
        )

        source["key"] = "changed"

        assertEquals("value", context.metadata["key"])
    }

    @Test
    fun `equal contexts compare as equal`() {
        val first: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
            requestId = RequestId("request-001"),
        )
        val second: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
            requestId = RequestId("request-001"),
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `construction performs no automatic identifier generation`() {
        val context: ExecutionContext = ExecutionContext(
            executionId = ExecutionId("execution-explicit"),
            correlationId = CorrelationId("corr-explicit"),
        )

        assertEquals("execution-explicit", context.executionId.value)
        assertEquals("corr-explicit", context.correlationId.value)
        assertNull(context.requestId)
    }
}
