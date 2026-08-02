@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileCircuitBreakerStateStoreTest {

    @Test
    fun `missing state is created and survives a new store instance`() = runTest {
        val directory = uniqueDirectory()
        val scope = CircuitBreakerScope.providerOperation(
            providerId = ProviderId("transport-primary"),
            operation = RetryOperation("transport.push"),
        )
        val state = closedState(scope, failures = 2)
        val first = AppleFileCircuitBreakerStateStore(directory)

        assertIs<CircuitBreakerLoadResult.Missing>(first.loadSuccess(scope))
        val created = first.compareSuccess(
            CircuitBreakerCompareAndSetRequest(
                scope = scope,
                expectedVersion = null,
                nextState = state,
            ),
        )
        val createdRecord = assertIs<CircuitBreakerCompareAndSetResult.Updated>(created).record
        assertEquals(0L, createdRecord.version)
        assertEquals(state, createdRecord.state)

        val reopened = AppleFileCircuitBreakerStateStore(directory)
        val found = assertIs<CircuitBreakerLoadResult.Found>(reopened.loadSuccess(scope))
        assertEquals(createdRecord, found.record)
    }

    @Test
    fun `compare and set preserves exact conflict evidence`() = runTest {
        val store = AppleFileCircuitBreakerStateStore(uniqueDirectory())
        val scope = CircuitBreakerScope.global()
        val initial = closedState(scope)
        val created = assertIs<CircuitBreakerCompareAndSetResult.Updated>(
            store.compareSuccess(
                CircuitBreakerCompareAndSetRequest(scope, null, initial),
            ),
        )

        val stale = store.compareSuccess(
            CircuitBreakerCompareAndSetRequest(
                scope = scope,
                expectedVersion = null,
                nextState = initial,
            ),
        )
        val conflict = assertIs<CircuitBreakerCompareAndSetResult.Conflict>(stale)
        assertEquals(created.record, conflict.current)

        val next = openState(scope)
        val updated = assertIs<CircuitBreakerCompareAndSetResult.Updated>(
            store.compareSuccess(
                CircuitBreakerCompareAndSetRequest(
                    scope = scope,
                    expectedVersion = created.record.version,
                    nextState = next,
                ),
            ),
        )
        assertEquals(1L, updated.record.version)
        assertEquals(next, updated.record.state)
    }

    @Test
    fun `two store instances serialize first creation exactly`() = runTest {
        val directory = uniqueDirectory()
        val scope = CircuitBreakerScope.workflow(WorkflowId("workflow-contention"))
        val request = CircuitBreakerCompareAndSetRequest(
            scope = scope,
            expectedVersion = null,
            nextState = closedState(scope),
        )
        val first = AppleFileCircuitBreakerStateStore(directory)
        val second = AppleFileCircuitBreakerStateStore(directory)

        val results = listOf(
            async(Dispatchers.Default) { first.compareSuccess(request) },
            async(Dispatchers.Default) { second.compareSuccess(request) },
        ).awaitAll()

        assertEquals(1, results.count { it is CircuitBreakerCompareAndSetResult.Updated })
        assertEquals(1, results.count { it is CircuitBreakerCompareAndSetResult.Conflict })
        val persisted = assertIs<CircuitBreakerLoadResult.Found>(first.loadSuccess(scope)).record
        assertEquals(0L, persisted.version)
        assertEquals(request.nextState, persisted.state)
        val conflict = assertIs<CircuitBreakerCompareAndSetResult.Conflict>(
            results.single { it is CircuitBreakerCompareAndSetResult.Conflict },
        )
        assertEquals(persisted, conflict.current)
    }

    @Test
    fun `all supported circuit scope shapes round trip without collision`() = runTest {
        val store = AppleFileCircuitBreakerStateStore(uniqueDirectory())
        val scopes = listOf(
            CircuitBreakerScope.global(),
            CircuitBreakerScope.provider(ProviderId("provider|one")),
            CircuitBreakerScope.providerOperation(
                ProviderId("provider|one"),
                RetryOperation("operation/one"),
            ),
            CircuitBreakerScope.tenantProviderOperation(
                TenantId("tenant-å"),
                ProviderId("provider|one"),
                RetryOperation("operation/one"),
            ),
            CircuitBreakerScope.workflow(WorkflowId("workflow-雪")),
        )

        scopes.forEachIndexed { index, scope ->
            val state = closedState(scope, failures = index)
            val result = store.compareSuccess(
                CircuitBreakerCompareAndSetRequest(scope, null, state),
            )
            assertIs<CircuitBreakerCompareAndSetResult.Updated>(result)
        }

        scopes.forEachIndexed { index, scope ->
            val found = assertIs<CircuitBreakerLoadResult.Found>(store.loadSuccess(scope))
            assertEquals(scope, found.record.state.scope)
            assertEquals(index, found.record.state.consecutiveFailures)
        }
    }

    @Test
    fun `half open probe lease and generation survive restart`() = runTest {
        val directory = uniqueDirectory()
        val scope = CircuitBreakerScope.provider(ProviderId("half-open-provider"))
        val state = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.HALF_OPEN,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = null,
            probeGeneration = 7L,
            probeInFlight = true,
            updatedAt = DataLoomInstant(4_000L),
            probeLeaseUntil = DataLoomInstant(5_000L),
        )
        AppleFileCircuitBreakerStateStore(directory).compareSuccess(
            CircuitBreakerCompareAndSetRequest(scope, null, state),
        )

        val found = assertIs<CircuitBreakerLoadResult.Found>(
            AppleFileCircuitBreakerStateStore(directory).loadSuccess(scope),
        )
        assertEquals(state, found.record.state)
        assertEquals(0L, found.record.version)
    }

    @Test
    fun `corrupt snapshot fails closed without leaking file content`() = runTest {
        val directory = uniqueDirectory()
        val store = AppleFileCircuitBreakerStateStore(directory)
        val scope = CircuitBreakerScope.global()
        assertIs<CircuitBreakerLoadResult.Missing>(store.loadSuccess(scope))
        val dataPath = "$directory/${AppleFileCircuitBreakerStateStore.DEFAULT_FILE_NAME}"
        writeUtf8FileAtomically(
            temporaryPath = "$dataPath.test-tmp",
            destinationPath = dataPath,
            content = "not-a-dataloom-circuit-snapshot\nsecret-payload",
        )

        val failure = assertIs<ProviderOperationResult.Failure>(store.load(scope))
        assertEquals("CIRCUIT_APPLE_STATE_CORRUPT", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
        assertTrue("secret-payload" !in failure.error.message)
        assertEquals(null, failure.error.cause)
    }

    @Test
    fun `version exhaustion fails before file access`() = runTest {
        val invalidDirectory = "/dev/null/dataloom-circuit-store"
        val store = AppleFileCircuitBreakerStateStore(invalidDirectory)
        val scope = CircuitBreakerScope.global()

        val failure = assertIs<ProviderOperationResult.Failure>(
            store.compareAndSet(
                CircuitBreakerCompareAndSetRequest(
                    scope = scope,
                    expectedVersion = Long.MAX_VALUE,
                    nextState = closedState(scope),
                ),
            ),
        )

        assertEquals("CIRCUIT_STATE_VERSION_EXHAUSTED", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `cancelled caller does not enter the store`() = runTest {
        val store = AppleFileCircuitBreakerStateStore(uniqueDirectory())
        val deferred = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            store.load(CircuitBreakerScope.global())
        }
        deferred.cancel(CancellationException("caller cancelled"))

        val failure = assertFailsWith<CancellationException> { deferred.await() }
        assertEquals("caller cancelled", failure.message)
    }

    @Test
    fun `constructor rejects unsafe paths without side effects`() {
        assertFailsWith<IllegalArgumentException> {
            AppleFileCircuitBreakerStateStore("relative/path")
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileCircuitBreakerStateStore("/tmp/safe", "../unsafe")
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileCircuitBreakerStateStore("/tmp/../unsafe")
        }
    }

    private suspend fun AppleFileCircuitBreakerStateStore.loadSuccess(
        scope: CircuitBreakerScope,
    ): CircuitBreakerLoadResult = assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
        load(scope),
    ).value

    private suspend fun AppleFileCircuitBreakerStateStore.compareSuccess(
        request: CircuitBreakerCompareAndSetRequest,
    ): CircuitBreakerCompareAndSetResult =
        assertIs<ProviderOperationResult.Success<CircuitBreakerCompareAndSetResult>>(
            compareAndSet(request),
        ).value

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-circuit-")
        append(NSUUID().UUIDString)
    }

    private fun closedState(
        scope: CircuitBreakerScope,
        failures: Int = 0,
    ): CircuitBreakerState = CircuitBreakerState(
        scope = scope,
        phase = CircuitBreakerPhase.CLOSED,
        consecutiveFailures = failures,
        failureWindowStartedAt = if (failures == 0) null else DataLoomInstant(900L),
        openUntil = null,
        probeGeneration = 0L,
        probeInFlight = false,
        updatedAt = DataLoomInstant(1_000L),
    )

    private fun openState(scope: CircuitBreakerScope): CircuitBreakerState = CircuitBreakerState(
        scope = scope,
        phase = CircuitBreakerPhase.OPEN,
        consecutiveFailures = 0,
        failureWindowStartedAt = null,
        openUntil = DataLoomInstant(3_000L),
        probeGeneration = 0L,
        probeInFlight = false,
        updatedAt = DataLoomInstant(2_000L),
    )
}
