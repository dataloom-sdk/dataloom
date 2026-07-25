package io.dataloom.testing.connectivity

import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.testing.FakeDataLoomError
import io.dataloom.testing.sampleConnectivityCheckRequest
import io.dataloom.testing.sampleConnectivitySnapshot
import io.dataloom.testing.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MutableConnectivityProviderTest {
    @Test
    fun `descriptor uses connectivity type`() {
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot())
        assertEquals(io.dataloom.api.provider.ProviderType.CONNECTIVITY, provider.descriptor.type)
    }

    @Test
    fun `returns initial snapshot by default`() {
        val snapshot = sampleConnectivitySnapshot()
        val provider = MutableConnectivityProvider(initialSnapshot = snapshot)
        assertEquals(ProviderOperationResult.Success(snapshot), runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) })
    }

    @Test
    fun `set snapshot updates future reads`() {
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot())
        val updated = sampleConnectivitySnapshot(status = ConnectivityStatus.LIMITED, isMetered = true)
        provider.setSnapshot(updated)
        assertEquals(ProviderOperationResult.Success(updated), runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) })
    }

    @Test
    fun `failure result overrides snapshot`() {
        val failure = ProviderOperationResult.Failure(FakeDataLoomError(message = "offline"))
        val provider = MutableConnectivityProvider(
            initialSnapshot = sampleConnectivitySnapshot(),
            failureResult = failure,
        )
        val result = runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) }
        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(failure, result)
    }

    @Test
    fun `records connectivity requests`() {
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot())
        val first = sampleConnectivityCheckRequest("001")
        val second = sampleConnectivityCheckRequest("002")
        runSuspend { provider.currentConnectivity(first) }
        runSuspend { provider.currentConnectivity(second) }
        assertEquals(listOf(first, second), provider.connectivityRequests)
    }

    @Test
    fun `clear recordings preserves snapshot`() {
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot())
        val updated = sampleConnectivitySnapshot(status = ConnectivityStatus.UNAVAILABLE, isMetered = null)
        provider.setSnapshot(updated)
        runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) }
        provider.clearRecordings()
        assertEquals(emptyList(), provider.connectivityRequests)
        assertEquals(ProviderOperationResult.Success(updated), runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest("next")) })
    }

    @Test
    fun `reset state restores initial snapshot`() {
        val initial = sampleConnectivitySnapshot(status = ConnectivityStatus.AVAILABLE, isMetered = false)
        val provider = MutableConnectivityProvider(initialSnapshot = initial)
        provider.setSnapshot(sampleConnectivitySnapshot(status = ConnectivityStatus.UNAVAILABLE, isMetered = null))
        provider.resetState()
        assertEquals(ProviderOperationResult.Success(initial), runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) })
    }

    @Test
    fun `reset state clears recordings`() {
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot())
        runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) }
        provider.resetState()
        assertEquals(emptyList(), provider.connectivityRequests)
    }

    @Test
    fun `supports metered snapshot updates`() {
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot())
        val updated = sampleConnectivitySnapshot(status = ConnectivityStatus.AVAILABLE, isMetered = true)
        provider.setSnapshot(updated)
        val result = runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) }
        assertEquals(ProviderOperationResult.Success(updated), result)
    }

    @Test
    fun `supports unknown snapshot updates`() {
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot())
        val updated = sampleConnectivitySnapshot(status = ConnectivityStatus.UNKNOWN, isMetered = null)
        provider.setSnapshot(updated)
        val result = runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) }
        assertEquals(ProviderOperationResult.Success(updated), result)
    }

    @Test
    fun `request order is preserved`() {
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot())
        val first = sampleConnectivityCheckRequest("a")
        val second = sampleConnectivityCheckRequest("b")
        runSuspend { provider.currentConnectivity(first) }
        runSuspend { provider.currentConnectivity(second) }
        assertEquals(first, provider.connectivityRequests.first())
        assertEquals(second, provider.connectivityRequests.last())
    }

    @Test
    fun `failure result remains stable after set snapshot`() {
        val failure = ProviderOperationResult.Failure(FakeDataLoomError(message = "always fail"))
        val provider = MutableConnectivityProvider(
            initialSnapshot = sampleConnectivitySnapshot(),
            failureResult = failure,
        )
        provider.setSnapshot(sampleConnectivitySnapshot(status = ConnectivityStatus.LIMITED, isMetered = true))
        assertEquals(failure, runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) })
    }

    @Test
    fun `multiple reads do not mutate snapshot`() {
        val snapshot = sampleConnectivitySnapshot()
        val provider = MutableConnectivityProvider(initialSnapshot = snapshot)
        assertEquals(ProviderOperationResult.Success(snapshot), runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest("1")) })
        assertEquals(ProviderOperationResult.Success(snapshot), runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest("2")) })
    }

    @Test
    fun `clear recordings does not affect failure behavior`() {
        val failure = ProviderOperationResult.Failure(FakeDataLoomError())
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot(), failureResult = failure)
        runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) }
        provider.clearRecordings()
        assertEquals(failure, runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest("again")) })
    }

    @Test
    fun `reset state keeps failure behavior unchanged`() {
        val failure = ProviderOperationResult.Failure(FakeDataLoomError())
        val provider = MutableConnectivityProvider(initialSnapshot = sampleConnectivitySnapshot(), failureResult = failure)
        provider.resetState()
        assertEquals(failure, runSuspend { provider.currentConnectivity(sampleConnectivityCheckRequest()) })
    }
}
