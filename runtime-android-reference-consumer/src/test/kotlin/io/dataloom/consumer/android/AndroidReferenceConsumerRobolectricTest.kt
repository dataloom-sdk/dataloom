package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
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
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Robolectric-backed runtime proof for [buildReferenceDataLoom], the first
 * real (non-compile-only) evidence closing part of `#101`'s
 * "Robolectric/instrumented runtime proof for the reference consumer"
 * gap.
 *
 * ## What this proves
 *
 * Everything below runs against a real, Robolectric-simulated Android
 * runtime, not a fake or a mock: [WorkManagerSchedulerProvider][io.dataloom.scheduler.workmanager.WorkManagerSchedulerProvider]
 * resolves a real `WorkManager` instance, [RoomStorageProvider][io.dataloom.storage.room.RoomStorageProvider]
 * and [RoomQueueProvider][io.dataloom.queue.room.RoomQueueProvider] each open
 * a real Room-backed SQLite database, and [AndroidConnectivityProvider][io.dataloom.connectivity.android.AndroidConnectivityProvider]
 * resolves a real `ConnectivityManager` service reference. If any of the
 * four providers' constructors or `initialize()`/`shutdown()`
 * implementations genuinely fail against Android framework code (not just
 * against DataLoom's own contracts), this test fails.
 *
 * [realAndroidProvidersApplyAPulledChangeToRealStorage] goes one step
 * further: it calls [DataLoom.synchronize] with a real inbound
 * [ChangeSet] and asserts the event was genuinely written to the real Room
 * database — `summary.inboundEventsApplied == 1`, not just a "succeeded"
 * status code. `SynchronizationExecutionCoordinator` defaults to
 * `SynchronizationConnectivityConfiguration.NONE` (confirmed by reading its
 * own KDoc, not assumed), so this does not depend on Robolectric's
 * `ConnectivityManager` shadow reporting a connected network.
 *
 * ## What this does not prove
 *
 * This does not run on a physical device or emulator; Robolectric
 * simulates the Android framework on the JVM, which is a real and useful
 * runtime signal but not identical to on-device behavior (timing,
 * process-death, and OS-version-specific quirks can still differ). Queue
 * admission, retry/circuit behavior, conflict detection, and the full
 * foreground/offline/cancellation/concurrency/resource-limit/migration/
 * termination/relaunch matrix `#101`'s acceptance criteria require remain
 * open — this proves one direct synchronization pass, not the complete
 * matrix.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReferenceConsumerRobolectricTest {

    @Test
    fun realAndroidProvidersInitializeAndShutDownCleanlyUnderRobolectric() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()

        // WorkManagerSchedulerProvider's constructor calls
        // WorkManager.getInstance(context) directly, so WorkManager must
        // already be initialized before buildReferenceDataLoom runs.
        // WorkManagerTestInitHelper is androidx.work's own documented
        // Robolectric/instrumented-test entry point for this — the real
        // production WorkManager.initialize() call requires a
        // Configuration.Provider-backed Application, which this bare test
        // context does not supply.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val dataLoom = buildReferenceDataLoom(context)

        val initializeResult = dataLoom.initialize()
        assertEquals(ProviderLifecycleResult.InitializeSuccess, initializeResult)

        val shutdownResult = dataLoom.shutdown()
        assertEquals(ProviderLifecycleResult.ShutdownSuccess, shutdownResult)
    }

    @Test
    fun realAndroidProvidersApplyAPulledChangeToRealStorage() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val changeSet = ChangeSet(
            id = ChangeSetId("robolectric-change-set-1"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("robolectric-event-1"),
                    entity = EntityReference(
                        type = EntityType("robolectric-entity"),
                        id = EntityId("robolectric-entity-1"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val dataLoom = buildReferenceDataLoom(context, OneChangeSetTransportProvider(changeSet))

        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val request = SynchronizationRequest(
            workflowId = WorkflowId("robolectric-workflow-1"),
            sessionId = SynchronizationSessionId("robolectric-session-1"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.FULL,
            context = ExecutionContext(
                executionId = ExecutionId("robolectric-execution-1"),
                correlationId = CorrelationId("robolectric-correlation-1"),
            ),
        )
        val executionResult = dataLoom.synchronize(request)

        val executed = assertIs<SynchronizationExecutionResult.Executed>(executionResult)
        val succeeded = assertIs<io.dataloom.api.synchronization.SynchronizationResult.Succeeded>(executed.result)
        assertEquals(1L, succeeded.summary.inboundEventsApplied)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }
}

/**
 * Test-only [TransportProvider] that always returns [changeSet] from
 * [pullChanges], proving a real synchronization pass genuinely applies
 * inbound changes to storage rather than only initializing/shutting down
 * cleanly. Push is not exercised by [AndroidReferenceConsumerRobolectricTest]
 * and fails deterministically if ever called.
 */
private class OneChangeSetTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.android.test.one-change-set-transport"),
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
