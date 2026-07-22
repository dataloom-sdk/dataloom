package io.dataloom.api.connectivity

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderVersion
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectivityContractsTest {

    // -------------------------------------------------------------------------
    // ConnectivityRequirement tests
    // -------------------------------------------------------------------------

    @Test
    fun `connectivity requirement contains NONE`() {
        val requirement: ConnectivityRequirement = ConnectivityRequirement.NONE
        assertEquals("NONE", requirement.name)
    }

    @Test
    fun `connectivity requirement contains AVAILABLE`() {
        val requirement: ConnectivityRequirement = ConnectivityRequirement.AVAILABLE
        assertEquals("AVAILABLE", requirement.name)
    }

    @Test
    fun `connectivity requirement contains UNMETERED`() {
        val requirement: ConnectivityRequirement = ConnectivityRequirement.UNMETERED
        assertEquals("UNMETERED", requirement.name)
    }

    @Test
    fun `connectivity requirement exposes all required values`() {
        val names: Set<String> = ConnectivityRequirement.entries.map { it.name }.toSet()
        assertTrue("NONE" in names)
        assertTrue("AVAILABLE" in names)
        assertTrue("UNMETERED" in names)
    }

    // -------------------------------------------------------------------------
    // ConnectivityStatus tests
    // -------------------------------------------------------------------------

    @Test
    fun `connectivity status contains UNKNOWN`() {
        val status: ConnectivityStatus = ConnectivityStatus.UNKNOWN
        assertEquals("UNKNOWN", status.name)
    }

    @Test
    fun `connectivity status contains UNAVAILABLE`() {
        val status: ConnectivityStatus = ConnectivityStatus.UNAVAILABLE
        assertEquals("UNAVAILABLE", status.name)
    }

    @Test
    fun `connectivity status contains AVAILABLE`() {
        val status: ConnectivityStatus = ConnectivityStatus.AVAILABLE
        assertEquals("AVAILABLE", status.name)
    }

    @Test
    fun `connectivity status contains LIMITED`() {
        val status: ConnectivityStatus = ConnectivityStatus.LIMITED
        assertEquals("LIMITED", status.name)
    }

    @Test
    fun `connectivity status exposes all required values`() {
        val names: Set<String> = ConnectivityStatus.entries.map { it.name }.toSet()
        assertTrue("UNKNOWN" in names)
        assertTrue("UNAVAILABLE" in names)
        assertTrue("AVAILABLE" in names)
        assertTrue("LIMITED" in names)
    }

    // -------------------------------------------------------------------------
    // ConnectivitySnapshot tests
    // -------------------------------------------------------------------------

    @Test
    fun `connectivity snapshot preserves status`() {
        val snapshot: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.AVAILABLE,
            isMetered = false,
        )
        assertEquals(ConnectivityStatus.AVAILABLE, snapshot.status)
    }

    @Test
    fun `connectivity snapshot supports isMetered true`() {
        val snapshot: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.AVAILABLE,
            isMetered = true,
        )
        assertEquals(true, snapshot.isMetered)
    }

    @Test
    fun `connectivity snapshot supports isMetered false`() {
        val snapshot: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.AVAILABLE,
            isMetered = false,
        )
        assertEquals(false, snapshot.isMetered)
    }

    @Test
    fun `connectivity snapshot supports isMetered null`() {
        val snapshot: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.UNKNOWN,
            isMetered = null,
        )
        assertNull(snapshot.isMetered)
    }

    @Test
    fun `connectivity snapshot metadata defaults to empty`() {
        val snapshot: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.UNAVAILABLE,
            isMetered = null,
        )
        assertEquals(DataLoomMetadata.Empty, snapshot.metadata)
        assertTrue(snapshot.metadata.isEmpty())
    }

    @Test
    fun `connectivity snapshot preserves explicit metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("provider" to "fake"))
        val snapshot: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.AVAILABLE,
            isMetered = false,
            metadata = metadata,
        )
        assertEquals(metadata, snapshot.metadata)
    }

    @Test
    fun `equal connectivity snapshots compare as equal`() {
        val a: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.AVAILABLE,
            isMetered = false,
        )
        val b: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.AVAILABLE,
            isMetered = false,
        )
        assertEquals(a, b)
    }

    @Test
    fun `different connectivity snapshots compare as not equal`() {
        val a: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.AVAILABLE,
            isMetered = false,
        )
        val b: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.UNAVAILABLE,
            isMetered = null,
        )
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ConnectivityCheckRequest tests
    // -------------------------------------------------------------------------

    @Test
    fun `connectivity check request preserves context`() {
        val context: ExecutionContext = sampleExecutionContext()
        val request: ConnectivityCheckRequest = ConnectivityCheckRequest(context = context)
        assertEquals(context, request.context)
    }

    @Test
    fun `equal connectivity check requests compare as equal`() {
        val context: ExecutionContext = sampleExecutionContext()
        val a: ConnectivityCheckRequest = ConnectivityCheckRequest(context = context)
        val b: ConnectivityCheckRequest = ConnectivityCheckRequest(context = context)
        assertEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ConnectivityProvider tests (fake implementation)
    // -------------------------------------------------------------------------

    @Test
    fun `connectivity provider descriptor uses CONNECTIVITY type`() {
        val provider: ConnectivityProvider = FakeConnectivityProvider()
        assertEquals(ProviderType.CONNECTIVITY, provider.descriptor.type)
    }

    @Test
    fun `connectivity provider returns available snapshot`() {
        val expected: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.AVAILABLE,
            isMetered = false,
        )
        val provider: ConnectivityProvider = FakeConnectivityProvider(
            connectivityResult = ProviderOperationResult.Success(expected),
        )
        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runSync {
            result = provider.currentConnectivity(
                ConnectivityCheckRequest(context = sampleExecutionContext()),
            )
        }
        val success: ProviderOperationResult.Success<ConnectivitySnapshot> = assertIs(result)
        assertEquals(ConnectivityStatus.AVAILABLE, success.value.status)
        assertEquals(false, success.value.isMetered)
    }

    @Test
    fun `connectivity provider returns unknown metering state`() {
        val expected: ConnectivitySnapshot = ConnectivitySnapshot(
            status = ConnectivityStatus.AVAILABLE,
            isMetered = null,
        )
        val provider: ConnectivityProvider = FakeConnectivityProvider(
            connectivityResult = ProviderOperationResult.Success(expected),
        )
        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runSync {
            result = provider.currentConnectivity(
                ConnectivityCheckRequest(context = sampleExecutionContext()),
            )
        }
        val success: ProviderOperationResult.Success<ConnectivitySnapshot> = assertIs(result)
        assertNull(success.value.isMetered)
    }

    @Test
    fun `connectivity provider can return canonical failure`() {
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("CONNECTIVITY_UNAVAILABLE"),
            category = ErrorCategory.PROVIDER,
            severity = ErrorSeverity.WARNING,
            recoverability = Recoverability.RECOVERABLE,
            message = "Connectivity provider could not determine network state.",
            cause = null,
        )
        val provider: ConnectivityProvider = FakeConnectivityProvider(
            connectivityResult = ProviderOperationResult.Failure(error),
        )
        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runSync {
            result = provider.currentConnectivity(
                ConnectivityCheckRequest(context = sampleExecutionContext()),
            )
        }
        val failure: ProviderOperationResult.Failure = assertIs(result)
        assertEquals(ErrorCategory.PROVIDER, failure.error.category)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleExecutionContext(): ExecutionContext = ExecutionContext(
        executionId = ExecutionId("execution-001"),
        correlationId = CorrelationId("corr-001"),
    )

    private fun runSync(block: suspend () -> Unit) {
        var exception: Throwable? = null
        val completed = arrayOf(false)
        block.startCoroutine(
            object : kotlin.coroutines.Continuation<Unit> {
                override val context: kotlin.coroutines.CoroutineContext =
                    kotlin.coroutines.EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {
                    result.onFailure { exception = it }
                    completed[0] = true
                }
            },
        )
        assertTrue(completed[0], "Coroutine did not complete synchronously")
        exception?.let { throw it }
    }

    private class FakeConnectivityProvider(
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("connectivity.fake"),
            name = ProviderName("Fake Connectivity Provider"),
            type = ProviderType.CONNECTIVITY,
            version = ProviderVersion("1.0.0"),
        ),
        private val connectivityResult: ProviderOperationResult<ConnectivitySnapshot> =
            ProviderOperationResult.Success(
                ConnectivitySnapshot(
                    status = ConnectivityStatus.AVAILABLE,
                    isMetered = false,
                ),
            ),
    ) : ConnectivityProvider {

        override suspend fun currentConnectivity(
            request: ConnectivityCheckRequest,
        ): ProviderOperationResult<ConnectivitySnapshot> = connectivityResult

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
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
