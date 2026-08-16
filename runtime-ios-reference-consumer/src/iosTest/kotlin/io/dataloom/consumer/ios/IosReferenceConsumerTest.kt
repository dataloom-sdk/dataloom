@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.consumer.ios

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Kotlin/Native runtime proof for [buildReferenceDataLoom], executed by
 * `iosSimulatorArm64Test`/`iosX64Test` on macOS CI — the iOS-side
 * counterpart to `AndroidReferenceConsumerRobolectricTest`.
 *
 * ## What this proves
 *
 * Everything below runs against a real Kotlin/Native iOS Simulator
 * runtime, not a fake or a mock: [AppleConnectivityProvider][io.dataloom.platform.ios.connectivity.AppleConnectivityProvider]
 * queries the real `NWPathMonitor` network path, [AppleSchedulerProvider][io.dataloom.platform.ios.scheduling.AppleSchedulerProvider]
 * constructs against real `BGTaskScheduler` bindings, [SqlDelightStorageProvider][io.dataloom.storage.sqldelight.SqlDelightStorageProvider]
 * opens a real SQLite database file via SQLDelight's native driver, and
 * [AppleFileQueueProvider][io.dataloom.runtime.queue.AppleFileQueueProvider]
 * reads/writes a real file under the supplied temporary directory. If any
 * of the four providers' constructors or `initialize()`/`shutdown()`
 * implementations genuinely fail against iOS platform APIs (not just
 * against DataLoom's own contracts), this test fails. Each test run uses a
 * fresh, UUID-suffixed directory and database/queue file names so repeated
 * runs never read stale state left by a previous run.
 *
 * [realIosProvidersApplyAPulledChangeToRealStorage] goes one step further:
 * it calls [DataLoom.synchronize][io.dataloom.runtime.facade.DataLoom.synchronize]
 * with a real inbound [ChangeSet] and asserts the event was genuinely
 * written to the real SQLite database — `summary.inboundEventsApplied == 1`,
 * not just a "succeeded" status code. `SynchronizationExecutionCoordinator`
 * defaults to `SynchronizationConnectivityConfiguration.NONE` (confirmed by
 * reading its own KDoc, not assumed), so this does not depend on the
 * Simulator's `NWPathMonitor` reporting a connected network.
 *
 * ## What this does not prove
 *
 * This does not run on a physical device; the iOS Simulator is a real,
 * Apple-provided runtime, not a shadow layer, but device-only behavior
 * (background execution limits, real network conditions, memory pressure)
 * can still differ. Queue admission, retry/circuit behavior, conflict
 * detection, and the full foreground/offline/cancellation/concurrency/
 * resource-limit/migration/termination/relaunch matrix `#101`'s acceptance
 * criteria require remain open — this proves one direct synchronization
 * pass, not the complete matrix.
 *
 * ## A note on how this was verified
 *
 * This test can be cross-compiled from a Windows development host
 * (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`), which
 * catches type errors and API drift, but **cannot be executed** there —
 * only a real macOS host with Xcode and the iOS Simulator can run
 * `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail
 * signal for this file's runtime behavior, not local cross-compilation
 * alone.
 */
class IosReferenceConsumerTest {

    @Test
    fun realIosProvidersInitializeAndShutDownCleanlyOnTheSimulator() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = buildString {
            append(NSTemporaryDirectory().trimEnd('/'))
            append("/dataloom-ios-reference-consumer-")
            append(runId)
        }

        val dataLoom = buildReferenceDataLoom(
            directoryPath = directoryPath,
            storageDatabaseName = "dataloom-storage-$runId.db",
            queueFileName = "dataloom-queue-$runId.tsv",
        )

        val initializeResult = dataLoom.initialize()
        assertEquals(ProviderLifecycleResult.InitializeSuccess, initializeResult)

        val shutdownResult = dataLoom.shutdown()
        assertEquals(ProviderLifecycleResult.ShutdownSuccess, shutdownResult)
    }

    @Test
    fun realIosProvidersApplyAPulledChangeToRealStorage() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = buildString {
            append(NSTemporaryDirectory().trimEnd('/'))
            append("/dataloom-ios-reference-consumer-sync-")
            append(runId)
        }

        val changeSet = ChangeSet(
            id = ChangeSetId("simulator-change-set-$runId"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("simulator-event-$runId"),
                    entity = EntityReference(
                        type = EntityType("simulator-entity"),
                        id = EntityId("simulator-entity-$runId"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )

        val dataLoom = buildReferenceDataLoom(
            directoryPath = directoryPath,
            storageDatabaseName = "dataloom-storage-sync-$runId.db",
            queueFileName = "dataloom-queue-sync-$runId.tsv",
            transportProvider = OneChangeSetTransportProvider(changeSet),
        )

        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val request = SynchronizationRequest(
            workflowId = WorkflowId("simulator-workflow-$runId"),
            sessionId = SynchronizationSessionId("simulator-session-$runId"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.FULL,
            context = ExecutionContext(
                executionId = ExecutionId("simulator-execution-$runId"),
                correlationId = CorrelationId("simulator-correlation-$runId"),
            ),
        )
        val executionResult = dataLoom.synchronize(request)

        val executed = assertIs<SynchronizationExecutionResult.Executed>(executionResult)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(executed.result)
        assertEquals(1L, succeeded.summary.inboundEventsApplied)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }
}

/**
 * Test-only [TransportProvider] that always returns [changeSet] from
 * [pullChanges], proving a real synchronization pass genuinely applies
 * inbound changes to storage rather than only initializing/shutting down
 * cleanly. Push is not exercised by [IosReferenceConsumerTest] and fails
 * deterministically if ever called.
 */
private class OneChangeSetTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.ios.test.one-change-set-transport"),
        name = ProviderName("One-Change-Set Test Transport"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("1.0.0"),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> =
        error("OneChangeSetTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> =
        ProviderOperationResult.Success(PullChangesResult.Changes(changeSet = changeSet, hasMore = false))
}
