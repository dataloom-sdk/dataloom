@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.retry

import io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand
import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCommandState
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetRequest
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetResult
import io.dataloom.api.circuit.CircuitAdministrationExecutionResult
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
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileCircuitAdministrationExecutorTest {

    @Test
    fun `reset atomically records exact result and replay does not mutate again`() = runTest {
        val directory = uniqueDirectory()
        val request = request("command-reset", CircuitAdministrationAction.RESET)
        val initial = closedState(request.scope, failures = 3, generation = 8L)
        createCircuit(directory, initial)
        authorize(directory, request)
        val command = command(request)

        val first = assertIs<CircuitAdministrationExecutionResult.Applied>(
            AppleFileCircuitAdministrationExecutor(directory, FixedClock(3_000L))
                .execute(command),
        )
        assertEquals(1L, first.record.version)
        assertEquals(CircuitBreakerPhase.CLOSED, first.record.state.phase)
        assertEquals(0, first.record.state.consecutiveFailures)
        assertNull(first.record.state.failureWindowStartedAt)
        assertEquals(8L, first.record.state.probeGeneration)
        assertEquals(DataLoomInstant(3_000L), first.record.state.updatedAt)

        val durableCommand = assertIs<CircuitAdministrationLoadResult.Found>(
            commandStore(directory).loadSuccess(request.commandId),
        ).record
        assertEquals(CircuitAdministrationCommandStatus.SUCCEEDED, durableCommand.state.status)
        assertEquals(first.record, durableCommand.state.resultingRecord)
        assertEquals(1L, durableCommand.version)

        val replay = assertIs<CircuitAdministrationExecutionResult.Applied>(
            AppleFileCircuitAdministrationExecutor(directory, FixedClock(9_000L))
                .execute(command),
        )
        assertEquals(first.record, replay.record)
        val circuit = assertIs<CircuitBreakerLoadResult.Found>(
            circuitStore(directory).loadSuccess(request.scope),
        ).record
        assertEquals(1L, circuit.version)
        assertEquals(first.record, circuit)
        assertEquals(1L, assertIs<CircuitAdministrationLoadResult.Found>(
            commandStore(directory).loadSuccess(request.commandId),
        ).record.version)
    }

    @Test
    fun `authorization mismatch and command conflict reject without circuit mutation`() = runTest {
        val directory = uniqueDirectory()
        val request = request("command-auth", CircuitAdministrationAction.RESET)
        val initial = closedState(request.scope, failures = 2, generation = 4L)
        val initialRecord = createCircuit(directory, initial)
        authorize(directory, request)
        val executor = AppleFileCircuitAdministrationExecutor(directory, FixedClock(3_000L))

        val mismatched = assertIs<CircuitAdministrationExecutionResult.Rejected>(
            executor.execute(
                AuthorizedCircuitAdministrationCommand(
                    request,
                    CircuitAdministrationAuthorizationId("different-authorization"),
                ),
            ),
        )
        assertEquals("CIRCUIT_ADMIN_AUTHORIZATION_MISMATCH", mismatched.reasonCode)

        val forgedRequest = request.copy(reason = CircuitAdministrationReason("forged reason"))
        val conflict = assertIs<CircuitAdministrationExecutionResult.Rejected>(
            executor.execute(command(forgedRequest)),
        )
        assertEquals("CIRCUIT_ADMIN_COMMAND_CONFLICT", conflict.reasonCode)
        assertEquals(
            initialRecord,
            assertIs<CircuitBreakerLoadResult.Found>(
                circuitStore(directory).loadSuccess(request.scope),
            ).record,
        )
        val durableCommand = assertIs<CircuitAdministrationLoadResult.Found>(
            commandStore(directory).loadSuccess(request.commandId),
        ).record
        assertEquals(CircuitAdministrationCommandStatus.AUTHORIZED, durableCommand.state.status)
        assertEquals(0L, durableCommand.version)
    }

    @Test
    fun `open uses bounded deadline and close preserves an existing closed failure window`() = runTest {
        val closeDirectory = uniqueDirectory()
        val closeRequest = request("command-close", CircuitAdministrationAction.CLOSE)
        createCircuit(closeDirectory, closedState(closeRequest.scope, 5, 6L))
        authorize(closeDirectory, closeRequest)
        val closed = assertIs<CircuitAdministrationExecutionResult.Applied>(
            AppleFileCircuitAdministrationExecutor(closeDirectory, FixedClock(3_000L))
                .execute(command(closeRequest)),
        ).record
        assertEquals(5, closed.state.consecutiveFailures)
        assertEquals(DataLoomInstant(500L), closed.state.failureWindowStartedAt)
        assertEquals(6L, closed.state.probeGeneration)

        val openDirectory = uniqueDirectory()
        val openRequest = request(
            "command-open",
            CircuitAdministrationAction.OPEN,
            openUntil = 6_000L,
        )
        createCircuit(openDirectory, closedState(openRequest.scope, 2, 7L))
        authorize(openDirectory, openRequest)
        val opened = assertIs<CircuitAdministrationExecutionResult.Applied>(
            AppleFileCircuitAdministrationExecutor(openDirectory, FixedClock(3_000L))
                .execute(command(openRequest)),
        ).record
        assertEquals(CircuitBreakerPhase.OPEN, opened.state.phase)
        assertEquals(DataLoomInstant(6_000L), opened.state.openUntil)
        assertEquals(0, opened.state.consecutiveFailures)
        assertEquals(7L, opened.state.probeGeneration)
    }

    @Test
    fun `expired open and clock regression fail closed without mutation`() = runTest {
        val expiredDirectory = uniqueDirectory()
        val expiredRequest = request(
            "command-expired",
            CircuitAdministrationAction.OPEN,
            openUntil = 3_000L,
        )
        authorize(expiredDirectory, expiredRequest)
        val expired = assertIs<CircuitAdministrationExecutionResult.Rejected>(
            AppleFileCircuitAdministrationExecutor(expiredDirectory, FixedClock(3_000L))
                .execute(command(expiredRequest)),
        )
        assertEquals("CIRCUIT_ADMINISTRATION_OPEN_DEADLINE_EXPIRED", expired.reasonCode)
        assertIs<CircuitBreakerLoadResult.Missing>(
            circuitStore(expiredDirectory).loadSuccess(expiredRequest.scope),
        )

        val regressionDirectory = uniqueDirectory()
        val regressionRequest = request("command-regression", CircuitAdministrationAction.RESET)
        authorize(regressionDirectory, regressionRequest)
        val regression = assertIs<CircuitAdministrationExecutionResult.Failed>(
            AppleFileCircuitAdministrationExecutor(regressionDirectory, FixedClock(1_500L))
                .execute(command(regressionRequest)),
        )
        assertEquals("CIRCUIT_ADMIN_EXECUTION_CLOCK_REGRESSION", regression.failure.code.value)
        assertIs<CircuitBreakerLoadResult.Missing>(
            circuitStore(regressionDirectory).loadSuccess(regressionRequest.scope),
        )
    }

    @Test
    fun `missing and non authorized commands reject`() = runTest {
        val directory = uniqueDirectory()
        val request = request("command-missing", CircuitAdministrationAction.RESET)
        val executor = AppleFileCircuitAdministrationExecutor(directory, FixedClock(3_000L))
        val missing = assertIs<CircuitAdministrationExecutionResult.Rejected>(
            executor.execute(command(request)),
        )
        assertEquals("CIRCUIT_ADMIN_COMMAND_MISSING", missing.reasonCode)

        val deniedState = CircuitAdministrationCommandState(
            request = request,
            status = CircuitAdministrationCommandStatus.AUTHORIZATION_DENIED,
            authorizationId = null,
            updatedAt = DataLoomInstant(2_000L),
            rejectionReasonCode = "PRINCIPAL_NOT_ALLOWED",
        )
        assertIs<CircuitAdministrationCompareAndSetResult.Updated>(
            commandStore(directory).compareSuccess(
                CircuitAdministrationCompareAndSetRequest(
                    request.commandId,
                    null,
                    deniedState,
                ),
            ),
        )
        val denied = assertIs<CircuitAdministrationExecutionResult.Rejected>(
            executor.execute(command(request)),
        )
        assertEquals("CIRCUIT_ADMIN_COMMAND_NOT_AUTHORIZED", denied.reasonCode)
    }

    @Test
    fun `legacy circuit snapshot upgrades without losing its state`() = runTest {
        val directory = uniqueDirectory()
        val dataPath = "$directory/${AppleFileCircuitBreakerStateStore.DEFAULT_FILE_NAME}"
        val legacy = "DATALOOM_CIRCUIT_STATE\t1\n" +
            "GLOBAL\t-\t-\t-\t-\tCLOSED\t2\t500\t-\t9\t0\t1000\t-\t0\n"
        appleCircuitWriteLegacySnapshot(directory, dataPath, legacy)
        val request = CircuitAdministrationRequest(
            commandId = CircuitAdministrationCommandId("command-legacy"),
            scope = CircuitBreakerScope.global(),
            principalId = CircuitAdministrationPrincipalId("principal-1"),
            requestedAt = DataLoomInstant(1_000L),
            action = CircuitAdministrationAction.RESET,
            reason = CircuitAdministrationReason("clear legacy failures"),
        )
        authorize(directory, request)

        val upgradedText = readUtf8FileOrNull(dataPath)
        assertTrue(checkNotNull(upgradedText).startsWith("DATALOOM_CIRCUIT_STATE\t2\n"))
        val beforeExecution = assertIs<CircuitBreakerLoadResult.Found>(
            circuitStore(directory).loadSuccess(request.scope),
        ).record
        assertEquals(2, beforeExecution.state.consecutiveFailures)
        assertEquals(9L, beforeExecution.state.probeGeneration)

        val applied = assertIs<CircuitAdministrationExecutionResult.Applied>(
            AppleFileCircuitAdministrationExecutor(directory, FixedClock(3_000L))
                .execute(command(request)),
        ).record
        assertEquals(1L, applied.version)
        assertEquals(9L, applied.state.probeGeneration)
    }

    private suspend fun createCircuit(
        directory: String,
        state: CircuitBreakerState,
    ) = assertIs<CircuitBreakerCompareAndSetResult.Updated>(
        circuitStore(directory).compareSuccess(
            CircuitBreakerCompareAndSetRequest(state.scope, null, state),
        ),
    ).record

    private suspend fun authorize(
        directory: String,
        request: CircuitAdministrationRequest,
    ) {
        assertIs<CircuitAdministrationCompareAndSetResult.Updated>(
            commandStore(directory).compareSuccess(
                CircuitAdministrationCompareAndSetRequest(
                    request.commandId,
                    null,
                    CircuitAdministrationCommandState(
                        request = request,
                        status = CircuitAdministrationCommandStatus.AUTHORIZED,
                        authorizationId = AUTHORIZATION_ID,
                        updatedAt = DataLoomInstant(2_000L),
                    ),
                ),
            ),
        )
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
        action: CircuitAdministrationAction,
        openUntil: Long? = null,
    ): CircuitAdministrationRequest = CircuitAdministrationRequest(
        commandId = CircuitAdministrationCommandId(commandId),
        scope = CircuitBreakerScope.providerOperation(
            ProviderId("provider-1"),
            RetryOperation("transport.push"),
        ),
        principalId = CircuitAdministrationPrincipalId("principal-1"),
        requestedAt = DataLoomInstant(1_000L),
        action = action,
        reason = CircuitAdministrationReason("operator approved $action"),
        openUntil = openUntil?.let(::DataLoomInstant),
    )

    private fun command(
        request: CircuitAdministrationRequest,
    ): AuthorizedCircuitAdministrationCommand = AuthorizedCircuitAdministrationCommand(
        request,
        AUTHORIZATION_ID,
    )

    private fun closedState(
        scope: CircuitBreakerScope,
        failures: Int,
        generation: Long,
    ): CircuitBreakerState = CircuitBreakerState(
        scope = scope,
        phase = CircuitBreakerPhase.CLOSED,
        consecutiveFailures = failures,
        failureWindowStartedAt = if (failures == 0) null else DataLoomInstant(500L),
        openUntil = null,
        probeGeneration = generation,
        probeInFlight = false,
        updatedAt = DataLoomInstant(1_000L),
    )

    private fun circuitStore(directory: String) = AppleFileCircuitBreakerStateStore(directory)

    private fun commandStore(directory: String) =
        AppleFileCircuitAdministrationStateStore(directory)

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-circuit-admin-executor-")
        append(NSUUID().UUIDString)
    }

    private suspend fun appleCircuitWriteLegacySnapshot(
        directory: String,
        dataPath: String,
        content: String,
    ) {
        val boundary = AppleCircuitStateFileBoundary(
            directory,
            AppleFileCircuitBreakerStateStore.DEFAULT_FILE_NAME,
        )
        // Establish the owner-only directory and lock before writing the legacy fixture.
        boundary.withExclusiveLock { Unit }
        writeUtf8FileAtomically("$dataPath.test-tmp", dataPath, content)
    }

    private class FixedClock(private val value: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(value)
    }

    private companion object {
        val AUTHORIZATION_ID = CircuitAdministrationAuthorizationId("authorization-1")
    }
}
