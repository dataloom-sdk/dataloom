package io.dataloom.runtime.facade

import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.AuthorizedConflictAdministrationCommand
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationDecision
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationId
import io.dataloom.api.conflict.ConflictAdministrationAuthorizer
import io.dataloom.api.conflict.ConflictAdministrationCommandId
import io.dataloom.api.conflict.ConflictAdministrationCommandStatus
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetRequest
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetResult
import io.dataloom.api.conflict.ConflictAdministrationExecutionResult
import io.dataloom.api.conflict.ConflictAdministrationExecutor
import io.dataloom.api.conflict.ConflictAdministrationLoadResult
import io.dataloom.api.conflict.ConflictAdministrationPrincipalId
import io.dataloom.api.conflict.ConflictAdministrationReason
import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.api.conflict.ConflictAdministrationStateRecord
import io.dataloom.api.conflict.ConflictAdministrationStateStore
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.model.ChangeOperation
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
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * Proves [DataLoomBuilder.conflictAdministrationConfiguration] actually
 * assembles and reaches [DataLoom.conflictAdministration] end to end, and
 * that leaving it unconfigured leaves the property `null` -- the same
 * default-off posture every other optional [DataLoom] capability already
 * follows.
 */
class DataLoomBuilderConflictAdministrationTest {

    private val entity = EntityReference(EntityType("document"), EntityId("doc-001"))
    private val conflictId = ConflictId("conflict-001")

    @Test
    fun conflictAdministrationConfiguration_whenNotConfigured_capabilityIsNull() {
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(TestStorageProvider(), TestTransportProvider())
            .defaultProviderBindings(bindings())
            .build()

        assertNull(dataLoom.conflictAdministration)
    }

    @Test
    fun conflictAdministrationConfiguration_whenConfigured_appliesAnAuthorizedManualDecision() = runBlocking {
        val unresolvedStore = InMemoryUnresolvedConflictStore()
        val resolvedStore = InMemoryResolvedConflictDecisionStore()
        val unresolvedLog = DurableUnresolvedConflictLog(unresolvedStore)
        unresolvedLog.record(
            conflictId,
            UnresolvedConflictRecord(
                conflictType = ConflictType.CONCURRENT_CHANGE,
                entity = entity,
                localChange = UnresolvedConflictChangeSummary(
                    changeEventId = ChangeEventId("event-local"),
                    operation = ChangeOperation.UPDATE,
                    metadata = DataLoomMetadata.Empty,
                ),
                remoteChange = UnresolvedConflictChangeSummary(
                    changeEventId = ChangeEventId("event-remote"),
                    operation = ChangeOperation.UPDATE,
                    metadata = DataLoomMetadata.Empty,
                ),
                conflictMetadata = DataLoomMetadata.Empty,
                reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
                committedAt = DataLoomInstant(500L),
            ),
        )

        val executor = RecordingExecutor()
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(TestStorageProvider(), TestTransportProvider())
            .defaultProviderBindings(bindings())
            .conflictAdministrationConfiguration(
                DataLoomConflictAdministrationSpec(
                    authorizer = AlwaysAuthorize(),
                    stateStore = InMemoryConflictAdministrationStore(),
                    executor = executor,
                    unresolvedConflictStore = unresolvedStore,
                    resolvedConflictDecisionStore = resolvedStore,
                ),
            )
            .build()

        val capability = checkNotNull(dataLoom.conflictAdministration)
        val result = capability.execute(
            ConflictAdministrationRequest(
                commandId = ConflictAdministrationCommandId("command-1"),
                conflictId = conflictId,
                principalId = ConflictAdministrationPrincipalId("operator-1"),
                requestedAt = DataLoomInstant(900L),
                decision = ConflictResolutionDecision.UseLocal(),
                reason = ConflictAdministrationReason("operator investigated and chose local"),
            ),
        )

        val succeeded = assertIs<io.dataloom.runtime.conflict.ConflictAdministrationResult.Succeeded>(result)
        assertEquals(ConflictAdministrationCommandStatus.SUCCEEDED, succeeded.record.state.status)
        assertEquals(1, executor.invocations)
    }

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = object : DataLoomClock {
            override fun now() = DataLoomInstant(1_000_000L)
        },
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("event-001") },
            queueEntryIds = generator { QueueEntryId("queue-001") },
            queueLeaseIds = generator { QueueLeaseId("lease-001") },
            conflictIds = generator { conflictId },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private fun bindings(): SynchronizationProviderBindings = SynchronizationProviderBindings(
        storageProviderId = ProviderId("storage-conflict-admin"),
        transportProviderId = ProviderId("transport-conflict-admin"),
    )

    private class TestStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage-conflict-admin"),
            name = ProviderName("Conflict Admin Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private class TestTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-conflict-admin"),
            name = ProviderName("Conflict Admin Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> = ProviderOperationResult.Failure(TestError())

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }

    private data class TestError(
        override val code: ErrorCode = ErrorCode("CONFLICT_ADMIN_BUILDER_TEST_ERROR"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Conflict admin builder test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class AlwaysAuthorize : ConflictAdministrationAuthorizer {
        override suspend fun authorize(
            request: ConflictAdministrationRequest,
        ): ConflictAdministrationAuthorizationDecision =
            ConflictAdministrationAuthorizationDecision.Authorized(
                ConflictAdministrationAuthorizationId("authorization-1"),
            )
    }

    private class RecordingExecutor : ConflictAdministrationExecutor {
        var invocations: Int = 0

        override suspend fun execute(
            command: AuthorizedConflictAdministrationCommand,
        ): ConflictAdministrationExecutionResult {
            invocations += 1
            return ConflictAdministrationExecutionResult.Applied
        }
    }

    private class InMemoryConflictAdministrationStore : ConflictAdministrationStateStore {
        private val records = mutableMapOf<ConflictAdministrationCommandId, ConflictAdministrationStateRecord>()

        override suspend fun load(
            commandId: ConflictAdministrationCommandId,
        ): ProviderOperationResult<ConflictAdministrationLoadResult> = ProviderOperationResult.Success(
            records[commandId]?.let(ConflictAdministrationLoadResult::Found)
                ?: ConflictAdministrationLoadResult.Missing,
        )

        override suspend fun compareAndSet(
            request: ConflictAdministrationCompareAndSetRequest,
        ): ProviderOperationResult<ConflictAdministrationCompareAndSetResult> {
            val current = records[request.commandId]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    ConflictAdministrationCompareAndSetResult.Conflict(current),
                )
            }
            val updated = ConflictAdministrationStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.commandId] = updated
            return ProviderOperationResult.Success(
                ConflictAdministrationCompareAndSetResult.Updated(updated),
            )
        }
    }

    private class InMemoryUnresolvedConflictStore : DurableStateStore<ConflictId, UnresolvedConflictRecord> {
        private val records = mutableMapOf<ConflictId, DurableStateRecord<UnresolvedConflictRecord>>()

        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<UnresolvedConflictRecord>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, UnresolvedConflictRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<UnresolvedConflictRecord>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private class InMemoryResolvedConflictDecisionStore : DurableStateStore<ConflictId, ResolvedConflictDecisionRecord> {
        private val records = mutableMapOf<ConflictId, DurableStateRecord<ResolvedConflictDecisionRecord>>()

        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<ResolvedConflictDecisionRecord>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, ResolvedConflictDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ResolvedConflictDecisionRecord>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }
}
