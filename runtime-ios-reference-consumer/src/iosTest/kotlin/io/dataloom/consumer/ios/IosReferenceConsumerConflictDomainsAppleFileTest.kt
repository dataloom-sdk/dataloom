@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.consumer.ios

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.ConflictQuarantineStatus
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableConflictQuarantineLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
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
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.conflict.ConflictOrchestrationBindings
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomConflictDetectionSpec
import io.dataloom.runtime.facade.DataLoomConflictQuarantineSpec
import io.dataloom.runtime.state.AppleFileDurableDomainStores
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Kotlin/Native iOS Simulator runtime proof that
 * [DataLoomConflictDetectionSpec]/`conflictDetectionConfiguration` genuinely
 * adopts [io.dataloom.runtime.state.AppleFileDurableStateStore] as real
 * backing storage, through a real [DataLoomBuilder], for the three durable
 * domains it covers in one capability: [DurableUnresolvedConflictLog],
 * [DurableResolvedConflictDecisionLog], and [DurableConflictQuarantineLog].
 *
 * Before this test, [AppleFileDurableDomainStores] (`#93`, 2026-09-28) gave
 * all three a real Apple-file production store with its own store-level iOS
 * proof, but nothing drove any of them through [DataLoomBuilder] on iOS --
 * only strategy-decision diagnostics had that bar. Each test here mirrors
 * [IosReferenceConsumerStrategyDiagnosticsAppleFileTest]'s shape: drive a
 * real [DataLoom.synchronize] PULL through a real [DataLoomBuilder] wired
 * with Apple-file-backed stores, then read the result back out through a
 * *second*, independently constructed store pointed at the same on-disk
 * file.
 *
 * ## What this does not prove
 *
 * A real caller reading this history from a production operations surface;
 * concurrent-writer contention against a real second OS process (see
 * `docs/apple/process-termination-investigation.md`); or the authorized
 * quarantine-release path (`DataLoomConflictAdministrationSpec`), which is
 * not exercised by this file.
 *
 * ## A note on how this was verified
 *
 * Like [IosReferenceConsumerStrategyDiagnosticsAppleFileTest], this file can
 * be cross-compiled from a Windows development host but **cannot be
 * executed** there -- only a real macOS host with Xcode and the iOS
 * Simulator can run `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail signal
 * for this file's runtime behavior, not local cross-compilation alone.
 */
class IosReferenceConsumerConflictDomainsAppleFileTest {

    private val invoice = EntityReference(EntityType("invoice"), EntityId("inv-1"))
    private val detectorId = ConflictDetectorId("ios-test-detector")
    private val resolverId = ConflictResolverId("ios-test-resolver")

    private fun local(entity: EntityReference) =
        ChangeEvent(ChangeEventId("local-${entity.id.value}"), entity, ChangeOperation.UPDATE)

    private fun remote(entity: EntityReference) =
        ChangeEvent(ChangeEventId("remote-${entity.id.value}"), entity, ChangeOperation.UPDATE)

    @Test
    fun unresolvedConflictIsDurablyRecordedToARealAppleFileStoreAndSurvivesRestart() = runBlocking {
        val runId = NSUUID().UUIDString
        val directoryPath = conflictDirectoryPath(runId)
        val unresolvedFile = "dataloom-unresolved-conflict-$runId.tsv"

        val dataLoom = conflictDataLoom(
            resolverDecision = ConflictResolutionDecision.Defer(),
            unresolvedStore = AppleFileDurableDomainStores.unresolvedConflictStore(directoryPath, unresolvedFile),
            resolvedStore = null,
            quarantineSpec = null,
        )
        dataLoom.initialize()
        val executed = assertIs<SynchronizationExecutionResult.Executed>(dataLoom.synchronize(pullRequest(runId)))
        val failed = assertIs<io.dataloom.api.synchronization.SynchronizationResult.Failed>(executed.result)
        assertEquals("DL-CONFLICT-DECISION-DEFERRED", failed.error.code.value)

        val conflictId = ConflictId("conflict-${invoice.id.value}-1")
        val restarted = DurableUnresolvedConflictLog(
            AppleFileDurableDomainStores.unresolvedConflictStore(directoryPath, unresolvedFile),
        )
        val recorded = assertIs<ProviderOperationResult.Success<io.dataloom.api.conflict.UnresolvedConflictRecord?>>(
            restarted.current(conflictId),
        )
        val record = assertNotNull(recorded.value)
        assertEquals(ConflictType.CONCURRENT_CHANGE, record.conflictType)
    }

    @Test
    fun resolvedConflictDecisionIsDurablyRecordedToARealAppleFileStoreAndSurvivesRestart() = runBlocking {
        val runId = NSUUID().UUIDString
        val directoryPath = conflictDirectoryPath(runId)
        val unresolvedFile = "dataloom-unresolved-conflict-resolved-$runId.tsv"
        val resolvedFile = "dataloom-resolved-conflict-decision-$runId.tsv"

        val dataLoom = conflictDataLoom(
            resolverDecision = ConflictResolutionDecision.UseRemote(),
            unresolvedStore = AppleFileDurableDomainStores.unresolvedConflictStore(directoryPath, unresolvedFile),
            resolvedStore = AppleFileDurableDomainStores.resolvedConflictDecisionStore(directoryPath, resolvedFile),
            quarantineSpec = null,
        )
        dataLoom.initialize()
        val executed = assertIs<SynchronizationExecutionResult.Executed>(dataLoom.synchronize(pullRequest(runId)))
        assertIs<io.dataloom.api.synchronization.SynchronizationResult.Succeeded>(executed.result)

        val conflictId = ConflictId("conflict-${invoice.id.value}-1")
        val restarted = DurableResolvedConflictDecisionLog(
            AppleFileDurableDomainStores.resolvedConflictDecisionStore(directoryPath, resolvedFile),
        )
        val recorded = assertIs<ProviderOperationResult.Success<io.dataloom.api.conflict.ResolvedConflictDecisionRecord?>>(
            restarted.current(conflictId),
        )
        val record = assertNotNull(recorded.value)
        assertEquals(resolverId, record.resolverId)
    }

    @Test
    fun repeatedConflictsAreQuarantinedInARealAppleFileStoreAndSurviveRestart() = runBlocking {
        val runId = NSUUID().UUIDString
        val directoryPath = conflictDirectoryPath(runId)
        val unresolvedFile = "dataloom-unresolved-conflict-quarantine-$runId.tsv"
        val resolvedFile = "dataloom-resolved-conflict-decision-quarantine-$runId.tsv"
        val quarantineFile = "dataloom-conflict-quarantine-$runId.tsv"

        val quarantineSpec = DataLoomConflictQuarantineSpec(
            store = AppleFileDurableDomainStores.conflictQuarantineStore(directoryPath, quarantineFile),
            policy = ConflictQuarantinePolicy(occurrenceThreshold = 2),
        )
        val dataLoom = conflictDataLoom(
            resolverDecision = ConflictResolutionDecision.Defer(),
            unresolvedStore = AppleFileDurableDomainStores.unresolvedConflictStore(directoryPath, unresolvedFile),
            resolvedStore = AppleFileDurableDomainStores.resolvedConflictDecisionStore(directoryPath, resolvedFile),
            quarantineSpec = quarantineSpec,
        )
        dataLoom.initialize()

        val codes = List(2) {
            val executed = assertIs<SynchronizationExecutionResult.Executed>(dataLoom.synchronize(pullRequest(runId)))
            val failed = assertIs<io.dataloom.api.synchronization.SynchronizationResult.Failed>(executed.result)
            failed.error.code.value
        }
        assertEquals(listOf("DL-CONFLICT-DECISION-DEFERRED", "DL-CONFLICT-QUARANTINED"), codes)

        val restarted = DurableConflictQuarantineLog(
            AppleFileDurableDomainStores.conflictQuarantineStore(directoryPath, quarantineFile),
        )
        val recorded = assertIs<ProviderOperationResult.Success<io.dataloom.api.conflict.ConflictQuarantineRecord?>>(
            restarted.current(ConflictQuarantineScope.of(invoice)),
        )
        val record = assertNotNull(recorded.value)
        assertEquals(ConflictQuarantineStatus.QUARANTINED, record.status)
        assertEquals(2, record.occurrenceCount)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun conflictDirectoryPath(runId: String): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-ios-reference-consumer-conflict-")
        append(runId)
    }

    private fun conflictDataLoom(
        resolverDecision: ConflictResolutionDecision,
        unresolvedStore: io.dataloom.api.state.DurableStateStore<ConflictId, io.dataloom.api.conflict.UnresolvedConflictRecord>,
        resolvedStore: io.dataloom.api.state.DurableStateStore<ConflictId, io.dataloom.api.conflict.ResolvedConflictDecisionRecord>?,
        quarantineSpec: DataLoomConflictQuarantineSpec?,
    ): DataLoom {
        val storage = RecordingStorageProvider(mapOf(invoice.id.value to local(invoice)))
        val transport = FakeTransportProvider(
            ProviderOperationResult.Success(
                PullChangesResult.Changes(
                    changeSet = ChangeSet(ChangeSetId("remote"), listOf(remote(invoice))),
                    hasMore = false,
                    nextCheckpoint = SynchronizationCheckpoint(
                        key = CheckpointKey("ios-conflict-workflow"),
                        token = CheckpointToken("next"),
                    ),
                ),
            ),
        )
        return DataLoomBuilder()
            .runtimeDependencies(conflictRuntimeDependencies())
            .providers(storage, transport)
            .defaultProviderBindings(
                SynchronizationProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .conflictDetectionConfiguration(
                DataLoomConflictDetectionSpec(
                    detectors = listOf(ConflictOnEveryPairDetector(detectorId)),
                    resolvers = listOf(FixedDecisionResolver(resolverId, resolverDecision)),
                    bindings = ConflictOrchestrationBindings(detectorId, resolverId),
                    unresolvedConflictStore = unresolvedStore,
                    resolvedConflictDecisionStore = resolvedStore,
                    quarantine = quarantineSpec,
                ),
            )
            .build()
    }

    private fun pullRequest(runId: String): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("ios-conflict-workflow-$runId"),
        sessionId = SynchronizationSessionId("ios-conflict-session-$runId"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("ios-conflict-execution-$runId"),
            correlationId = CorrelationId("ios-conflict-correlation-$runId"),
        ),
    )

    private fun conflictRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = AppleDataLoomClock(),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("ios-conflict-event") },
            queueEntryIds = generator { QueueEntryId("ios-conflict-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("ios-conflict-queue-lease") },
            conflictIds = generator { ConflictId("ios-conflict-unused") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    /**
     * Reports a conflict with a fresh ID per detection -- same shape as
     * `DataLoomBuilderConflictQuarantineTest`'s own detector fixture, which
     * this test file deliberately mirrors.
     */
    private class ConflictOnEveryPairDetector(override val id: ConflictDetectorId) : ConflictDetector {
        private var detections = 0

        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
            ConflictDetectionResult.ConflictDetected(
                SynchronizationConflict(
                    id = ConflictId("conflict-${request.localChange.entity.id.value}-${++detections}"),
                    type = ConflictType.CONCURRENT_CHANGE,
                    entity = request.localChange.entity,
                    localChange = request.localChange,
                    remoteChange = request.remoteChange,
                ),
            )
    }

    private class FixedDecisionResolver(
        override val id: ConflictResolverId,
        private val decision: ConflictResolutionDecision,
    ) : ConflictResolver {
        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision = decision
    }

    private class RecordingStorageProvider(
        private val localCandidates: Map<String, ChangeEvent>,
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("ios-conflict-storage"),
            name = ProviderName("iOS conflict storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readLocalConflictCandidate(
            request: LocalConflictCandidateReadRequest,
        ): ProviderOperationResult<LocalConflictCandidateReadResult> {
            val local = localCandidates[request.entity.id.value]
            return ProviderOperationResult.Success(
                if (local == null) {
                    LocalConflictCandidateReadResult.NotFound
                } else {
                    LocalConflictCandidateReadResult.Found(local)
                },
            )
        }
    }

    private class FakeTransportProvider(
        private val pullResult: ProviderOperationResult<PullChangesResult>,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("ios-conflict-transport"),
            name = ProviderName("iOS conflict transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<io.dataloom.api.synchronization.ChangeSetAcknowledgement> =
            error("push is not used by this pull-only test")

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            pullResult
    }
}
