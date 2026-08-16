package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Gradle Managed Device runtime proof for [buildReferenceDataLoom] — the
 * real-emulator counterpart to `AndroidReferenceConsumerRobolectricTest`.
 *
 * ## What this proves, beyond the Robolectric tests
 *
 * `AndroidReferenceConsumerRobolectricTest` already proves initialize/
 * shutdown and a `synchronize()` PULL pass against a Robolectric-simulated
 * Android runtime — a real and useful JVM-hosted shadow layer, but not a
 * genuine Android OS process. This class runs the identical two proofs
 * against `pixel2Api35`, a real Gradle Managed Device AVD emulator (API 35,
 * AOSP, x86_64) executed on the real Linux CI runner via KVM — the same
 * managed-device mechanism `dataloom-queue-room`'s and
 * `dataloom-storage-room`'s own instrumented tests already use (see
 * `RoomStorageProviderInstrumentedTest`). This is genuine on-emulator
 * execution: a real `android.app.Application` process, a real
 * `WorkManager` instance, a real Room-backed SQLite database on a real
 * filesystem, and a real `ConnectivityManager` service — not a JVM shadow.
 *
 * Each test run uses a UUID-suffixed database/queue name so repeated CI
 * runs never read stale state left by a previous run on the same emulator
 * image.
 *
 * ## What this still does not prove
 *
 * This does not run on a physical device — an AVD emulator is closer to
 * real hardware than Robolectric, but background execution limits, real
 * network conditions, and manufacturer-specific OS behavior can still
 * differ from an actual phone or tablet. It does not exercise queue
 * admission, retry/circuit behavior, or conflict detection, and it does
 * not prove the full foreground/offline/cancellation/concurrency/
 * resource-limit/migration/termination/relaunch matrix `#101`'s acceptance
 * criteria require — this proves the same bounded initialize/shutdown/
 * synchronize() slice `AndroidReferenceConsumerRobolectricTest` proves,
 * against a more realistic runtime.
 *
 * ## A note on how this was verified
 *
 * This test can be compiled from a Windows development host, but actually
 * **running** it requires a Gradle Managed Device, which itself requires
 * KVM (Linux-only) — this repository's `android-validation.yml` CI job
 * enables KVM on the `ubuntu-latest` runner specifically for this purpose
 * (the same setup `dataloom-queue-room`'s and `dataloom-storage-room`'s
 * managed-device tests already rely on). Local Windows verification is
 * limited to confirming this file compiles; the real pass/fail signal is
 * this PR/commit's own `pixel2Api35DebugAndroidTest` CI run.
 */
@RunWith(AndroidJUnit4::class)
class AndroidReferenceConsumerInstrumentedTest {

    @Test
    fun realAndroidProvidersInitializeAndShutDownCleanlyOnAManagedDevice() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val runId = UUID.randomUUID().toString()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val dataLoom = buildReferenceDataLoom(
            context = context,
            storageDatabaseName = "dataloom-storage-managed-device-$runId.db",
            queueDatabaseName = "dataloom-queue-managed-device-$runId.db",
        )

        val initializeResult = dataLoom.initialize()
        assertEquals(ProviderLifecycleResult.InitializeSuccess, initializeResult)

        val shutdownResult = dataLoom.shutdown()
        assertEquals(ProviderLifecycleResult.ShutdownSuccess, shutdownResult)
    }

    @Test
    fun realAndroidProvidersApplyAPulledChangeToRealStorageOnAManagedDevice() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val runId = UUID.randomUUID().toString()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val changeSet = ChangeSet(
            id = ChangeSetId("managed-device-change-set-$runId"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("managed-device-event-$runId"),
                    entity = EntityReference(
                        type = EntityType("managed-device-entity"),
                        id = EntityId("managed-device-entity-$runId"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val dataLoom = buildReferenceDataLoom(
            context = context,
            transportProvider = OneChangeSetTransportProvider(changeSet),
            storageDatabaseName = "dataloom-storage-managed-device-sync-$runId.db",
            queueDatabaseName = "dataloom-queue-managed-device-sync-$runId.db",
        )

        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val request = SynchronizationRequest(
            workflowId = WorkflowId("managed-device-workflow-$runId"),
            sessionId = SynchronizationSessionId("managed-device-session-$runId"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.FULL,
            context = ExecutionContext(
                executionId = ExecutionId("managed-device-execution-$runId"),
                correlationId = CorrelationId("managed-device-correlation-$runId"),
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
 * cleanly. Push is not exercised by
 * [AndroidReferenceConsumerInstrumentedTest] and fails deterministically if
 * ever called.
 */
private class OneChangeSetTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.android.test.instrumented-one-change-set-transport"),
        name = ProviderName("One-Change-Set Instrumented Test Transport"),
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
