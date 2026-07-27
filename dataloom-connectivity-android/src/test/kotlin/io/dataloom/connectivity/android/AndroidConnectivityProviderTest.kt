package io.dataloom.connectivity.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidConnectivityProviderTest {

    private val mockNetwork: Network = mock()
    private val mockCapabilities: NetworkCapabilities = mock()
    private val mockConnectivityManager: ConnectivityManager = mock()
    private val mockContext: Context = mock()

    private val provider = AndroidConnectivityProvider(mockContext, sdkInt = 23)

    private fun setupConnected(
        hasInternet: Boolean = true,
        isValidated: Boolean = true,
        isNotMetered: Boolean = false,
    ) {
        whenever(mockContext.getSystemService(Context.CONNECTIVITY_SERVICE))
            .thenReturn(mockConnectivityManager)
        whenever(mockConnectivityManager.activeNetwork).thenReturn(mockNetwork)
        whenever(mockConnectivityManager.getNetworkCapabilities(mockNetwork))
            .thenReturn(mockCapabilities)
        whenever(mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .thenReturn(hasInternet)
        whenever(mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .thenReturn(isValidated)
        whenever(mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            .thenReturn(isNotMetered)
    }

    private fun makeRequest(): ConnectivityCheckRequest = ConnectivityCheckRequest(
        context = ExecutionContext(
            executionId = ExecutionId("test-exec-id"),
            correlationId = CorrelationId("test-corr-id"),
            metadata = DataLoomMetadata.Empty,
        ),
    )

    @Test
    fun `descriptor type is CONNECTIVITY`() {
        assertEquals(ProviderType.CONNECTIVITY, provider.descriptor.type)
    }

    @Test
    fun `descriptor provider id is non-blank`() {
        assertTrue(provider.descriptor.id.value.isNotBlank())
    }

    @Test
    fun `currentConnectivity returns AVAILABLE when internet and validated`() {
        setupConnected(hasInternet = true, isValidated = true, isNotMetered = true)

        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runTest { result = provider.currentConnectivity(makeRequest()) }

        val success = assertIs<ProviderOperationResult.Success<ConnectivitySnapshot>>(result)
        assertEquals(ConnectivityStatus.AVAILABLE, success.value.status)
        assertFalse(success.value.isMetered!!)
    }

    @Test
    fun `currentConnectivity returns AVAILABLE and metered when not-metered capability absent`() {
        setupConnected(hasInternet = true, isValidated = true, isNotMetered = false)

        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runTest { result = provider.currentConnectivity(makeRequest()) }

        val success = assertIs<ProviderOperationResult.Success<ConnectivitySnapshot>>(result)
        assertEquals(ConnectivityStatus.AVAILABLE, success.value.status)
        assertTrue(success.value.isMetered!!)
    }

    @Test
    fun `currentConnectivity returns LIMITED when internet but not validated`() {
        setupConnected(hasInternet = true, isValidated = false)

        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runTest { result = provider.currentConnectivity(makeRequest()) }

        val success = assertIs<ProviderOperationResult.Success<ConnectivitySnapshot>>(result)
        assertEquals(ConnectivityStatus.LIMITED, success.value.status)
    }

    @Test
    fun `currentConnectivity returns LIMITED when no internet capability`() {
        setupConnected(hasInternet = false, isValidated = false)

        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runTest { result = provider.currentConnectivity(makeRequest()) }

        val success = assertIs<ProviderOperationResult.Success<ConnectivitySnapshot>>(result)
        assertEquals(ConnectivityStatus.LIMITED, success.value.status)
    }

    @Test
    fun `currentConnectivity returns UNAVAILABLE when no active network`() {
        whenever(mockContext.getSystemService(Context.CONNECTIVITY_SERVICE))
            .thenReturn(mockConnectivityManager)
        whenever(mockConnectivityManager.activeNetwork).thenReturn(null)

        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runTest { result = provider.currentConnectivity(makeRequest()) }

        val success = assertIs<ProviderOperationResult.Success<ConnectivitySnapshot>>(result)
        assertEquals(ConnectivityStatus.UNAVAILABLE, success.value.status)
        assertNull(success.value.isMetered)
    }

    @Test
    fun `currentConnectivity returns UNKNOWN when ConnectivityManager is null`() {
        whenever(mockContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(null)

        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runTest { result = provider.currentConnectivity(makeRequest()) }

        val success = assertIs<ProviderOperationResult.Success<ConnectivitySnapshot>>(result)
        assertEquals(ConnectivityStatus.UNKNOWN, success.value.status)
        assertNull(success.value.isMetered)
    }

    @Test
    fun `currentConnectivity returns UNKNOWN when capabilities are null`() {
        whenever(mockContext.getSystemService(Context.CONNECTIVITY_SERVICE))
            .thenReturn(mockConnectivityManager)
        whenever(mockConnectivityManager.activeNetwork).thenReturn(mockNetwork)
        whenever(mockConnectivityManager.getNetworkCapabilities(mockNetwork)).thenReturn(null)

        var result: ProviderOperationResult<ConnectivitySnapshot>? = null
        runTest { result = provider.currentConnectivity(makeRequest()) }

        val success = assertIs<ProviderOperationResult.Success<ConnectivitySnapshot>>(result)
        assertEquals(ConnectivityStatus.UNKNOWN, success.value.status)
        assertNull(success.value.isMetered)
    }

    @Test
    fun `initialize returns Success`() {
        var result: ProviderOperationResult<Unit>? = null
        runTest { result = provider.initialize(io.dataloom.api.provider.ProviderInitializationContext()) }
        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `health returns Success`() {
        var result: ProviderOperationResult<*>? = null
        runTest { result = provider.health() }
        assertIs<ProviderOperationResult.Success<*>>(result)
    }

    @Test
    fun `close returns Success`() {
        var result: ProviderOperationResult<Unit>? = null
        runTest { result = provider.close() }
        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }
}
