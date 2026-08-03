@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCommandState
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetRequest
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetResult
import io.dataloom.api.circuit.CircuitAdministrationFailureSnapshot
import io.dataloom.api.circuit.CircuitAdministrationLoadResult
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileCircuitAdministrationStateStoreTest {

    @Test
    fun `command and circuit records survive independent store updates`() = runTest {
        val directory = uniqueDirectory()
        val circuitStore = AppleFileCircuitBreakerStateStore(directory)
        val commandStore = AppleFileCircuitAdministrationStateStore(directory)
        val request = request("command-shared-snapshot")
        val circuit = closedState(request.scope, failures = 2)

        val circuitCreated = assertIs<CircuitBreakerCompareAndSetResult.Updated>(
            circuitStore.compareSuccess(
                CircuitBreakerCompareAndSetRequest(request.scope, null, circuit),
            ),
        )
        val commandCreated = assertIs<CircuitAdministrationCompareAndSetResult.Updated>(
            commandStore.compareSuccess(
                CircuitAdministrationCompareAndSetRequest(
                    request.commandId,
                    null,
                    authorizedState(request),
                ),
            ),
        )

        val nextCircuit = circuit.copy(updatedAt = DataLoomInstant(2_500L))
        assertIs<CircuitBreakerCompareAndSetResult.Updated>(
            circuitStore.compareSuccess(
                CircuitBreakerCompareAndSetRequest(
                    request.scope,
                    circuitCreated.record.version,
                    nextCircuit,
                ),
            ),
        )

        val reopenedCommand = AppleFileCircuitAdministrationStateStore(directory)
        val foundCommand = assertIs<CircuitAdministrationLoadResult.Found>(
            reopenedCommand.loadSuccess(request.commandId),
        )
        assertEquals(commandCreated.record, foundCommand.record)
        val foundCircuit = assertIs<CircuitBreakerLoadResult.Found>(
            AppleFileCircuitBreakerStateStore(directory).loadSuccess(request.scope),
        )
        assertEquals(nextCircuit, foundCircuit.record.state)
        assertEquals(1L, foundCircuit.record.version)
    }

    @Test
    fun `compare and set preserves conflicts and immutable command input`() = runTest {
        val store = AppleFileCircuitAdministrationStateStore(uniqueDirectory())
        val request = request("command-cas", reason = "approved reset")
        val created = assertIs<CircuitAdministrationCompareAndSetResult.Updated>(
            store.compareSuccess(
                CircuitAdministrationCompareAndSetRequest(
                    request.commandId,
                    null,
                    authorizedState(request),
                ),
            ),
        )
        assertEquals(0L, created.record.version)

        val stale = assertIs<CircuitAdministrationCompareAndSetResult.Conflict>(
            store.compareSuccess(
                CircuitAdministrationCompareAndSetRequest(
                    request.commandId,
                    null,
                    authorizedState(request),
                ),
            ),
        )
        assertEquals(created.record, stale.current)

        val forged = request("command-cas", reason = "forged replacement")
        val conflict = assertIs<CircuitAdministrationCompareAndSetResult.Conflict>(
            store.compareSuccess(
                CircuitAdministrationCompareAndSetRequest(
                    request.commandId,
                    created.record.version,
                    authorizedState(forged),
                ),
            ),
        )
        assertEquals(created.record, conflict.current)
    }

    @Test
    fun `every durable command status shape round trips`() = runTest {
        val store = AppleFileCircuitAdministrationStateStore(uniqueDirectory())
        val result = CircuitBreakerStateRecord(
            state = closedState(request("result-source").scope),
            version = 7L,
        )
        val states = listOf(
            authorizedState(request("command-authorized")),
            authorizedState(request("command-succeeded")).copy(
                status = CircuitAdministrationCommandStatus.SUCCEEDED,
                resultingRecord = result.copy(
                    state = result.state.copy(scope = request("command-succeeded").scope),
                ),
            ),
            deniedState(request("command-denied")),
            authorizedState(request("command-policy")).copy(
                status = CircuitAdministrationCommandStatus.POLICY_REJECTED,
                rejectionReasonCode = "CHANGE_WINDOW_CLOSED",
            ),
            authorizedState(request("command-rejected")).copy(
                status = CircuitAdministrationCommandStatus.EXECUTION_REJECTED,
                rejectionReasonCode = "CIRCUIT_ADMIN_COMMAND_CONFLICT",
            ),
            authorizedState(request("command-failed")).copy(
                status = CircuitAdministrationCommandStatus.EXECUTION_FAILED,
                executionFailure = CircuitAdministrationFailureSnapshot(
                    code = ErrorCode("CIRCUIT_WRITE_FAILED"),
                    category = ErrorCategory.STORAGE,
                    severity = ErrorSeverity.ERROR,
                    recoverability = Recoverability.RECOVERABLE,
                ),
            ),
        )

        states.forEach { state ->
            assertIs<CircuitAdministrationCompareAndSetResult.Updated>(
                store.compareSuccess(
                    CircuitAdministrationCompareAndSetRequest(
                        state.request.commandId,
                        null,
                        state,
                    ),
                ),
            )
        }
        states.forEach { expected ->
            val found = assertIs<CircuitAdministrationLoadResult.Found>(
                store.loadSuccess(expected.request.commandId),
            )
            assertEquals(expected, found.record.state)
            assertEquals(0L, found.record.version)
        }
    }

    @Test
    fun `corrupt snapshot fails closed without leaking file content`() = runTest {
        val directory = uniqueDirectory()
        val store = AppleFileCircuitAdministrationStateStore(directory)
        val commandId = CircuitAdministrationCommandId("command-corrupt")
        assertIs<CircuitAdministrationLoadResult.Missing>(store.loadSuccess(commandId))
        val dataPath = "$directory/${AppleFileCircuitAdministrationStateStore.DEFAULT_FILE_NAME}"
        writeUtf8FileAtomically(
            temporaryPath = "$dataPath.test-tmp",
            destinationPath = dataPath,
            content = "not-a-dataloom-circuit-snapshot\nsecret-audit-value",
        )

        val failure = assertIs<ProviderOperationResult.Failure>(store.load(commandId))
        assertEquals("CIRCUIT_ADMIN_APPLE_STATE_CORRUPT", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
        assertTrue("secret-audit-value" !in failure.error.message)
        assertEquals(null, failure.error.cause)
    }

    @Test
    fun `version exhaustion fails before file access and cancellation propagates`() = runTest {
        val request = request("command-exhausted")
        val invalidStore = AppleFileCircuitAdministrationStateStore(
            "/dev/null/dataloom-circuit-administration",
        )
        val failure = assertIs<ProviderOperationResult.Failure>(
            invalidStore.compareAndSet(
                CircuitAdministrationCompareAndSetRequest(
                    request.commandId,
                    Long.MAX_VALUE,
                    authorizedState(request),
                ),
            ),
        )
        assertEquals("CIRCUIT_ADMIN_STATE_VERSION_EXHAUSTED", failure.error.code.value)

        val deferred = async(start = CoroutineStart.LAZY) {
            AppleFileCircuitAdministrationStateStore(uniqueDirectory())
                .load(CircuitAdministrationCommandId("command-cancelled"))
        }
        deferred.cancel(CancellationException("caller cancelled"))
        assertEquals(
            "caller cancelled",
            assertFailsWith<CancellationException> { deferred.await() }.message,
        )
    }

    @Test
    fun `constructor rejects unsafe paths`() {
        assertFailsWith<IllegalArgumentException> {
            AppleFileCircuitAdministrationStateStore("relative/path")
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileCircuitAdministrationStateStore("/tmp/safe", "../unsafe")
        }
    }

    private suspend fun AppleFileCircuitAdministrationStateStore.loadSuccess(
        commandId: CircuitAdministrationCommandId,
    ): CircuitAdministrationLoadResult =
        assertIs<ProviderOperationResult.Success<CircuitAdministrationLoadResult>>(
            load(commandId),
        ).value

    private suspend fun AppleFileCircuitAdministrationStateStore.compareSuccess(
        request: CircuitAdministrationCompareAndSetRequest,
    ): CircuitAdministrationCompareAndSetResult =
        assertIs<ProviderOperationResult.Success<CircuitAdministrationCompareAndSetResult>>(
            compareAndSet(request),
        ).value

    private suspend fun AppleFileCircuitBreakerStateStore.loadSuccess(
        scope: CircuitBreakerScope,
    ): CircuitBreakerLoadResult =
        assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(load(scope)).value

    private suspend fun AppleFileCircuitBreakerStateStore.compareSuccess(
        request: CircuitBreakerCompareAndSetRequest,
    ): CircuitBreakerCompareAndSetResult =
        assertIs<ProviderOperationResult.Success<CircuitBreakerCompareAndSetResult>>(
            compareAndSet(request),
        ).value

    private fun request(
        commandId: String,
        reason: String = "operator approved reset",
    ): CircuitAdministrationRequest = CircuitAdministrationRequest(
        commandId = CircuitAdministrationCommandId(commandId),
        scope = CircuitBreakerScope.providerOperation(
            ProviderId("provider-å"),
            RetryOperation("transport.push"),
        ),
        principalId = CircuitAdministrationPrincipalId("principal-雪"),
        requestedAt = DataLoomInstant(1_000L),
        action = CircuitAdministrationAction.RESET,
        reason = CircuitAdministrationReason(reason),
    )

    private fun authorizedState(
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationCommandState = CircuitAdministrationCommandState(
        request = request,
        status = CircuitAdministrationCommandStatus.AUTHORIZED,
        authorizationId = CircuitAdministrationAuthorizationId("authorization-雪"),
        updatedAt = DataLoomInstant(2_000L),
    )

    private fun deniedState(
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationCommandState = CircuitAdministrationCommandState(
        request = request,
        status = CircuitAdministrationCommandStatus.AUTHORIZATION_DENIED,
        authorizationId = null,
        updatedAt = DataLoomInstant(2_000L),
        rejectionReasonCode = "PRINCIPAL_NOT_ALLOWED",
    )

    private fun closedState(
        scope: CircuitBreakerScope,
        failures: Int = 0,
    ): CircuitBreakerState = CircuitBreakerState(
        scope = scope,
        phase = CircuitBreakerPhase.CLOSED,
        consecutiveFailures = failures,
        failureWindowStartedAt = if (failures == 0) null else DataLoomInstant(500L),
        openUntil = null,
        probeGeneration = 4L,
        probeInFlight = false,
        updatedAt = DataLoomInstant(1_000L),
    )

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-circuit-admin-")
        append(NSUUID().UUIDString)
    }
}
