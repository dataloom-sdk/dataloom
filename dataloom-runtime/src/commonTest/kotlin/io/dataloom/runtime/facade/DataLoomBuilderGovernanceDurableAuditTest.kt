package io.dataloom.runtime.facade

import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.security.DataLoomHmacCalculator
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.governance.audit.AuditChainState
import io.dataloom.governance.audit.AuditChainVerifier
import io.dataloom.governance.audit.AuditEvent
import io.dataloom.governance.audit.AuditEventType
import io.dataloom.governance.audit.AuditStoreScope
import io.dataloom.governance.audit.AuditVerificationResult
import io.dataloom.governance.audit.DurableAuditStore
import io.dataloom.governance.rbac.PrincipalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

/**
 * Proves [DurableAuditStore] plugs into [DataLoomBuilder.governanceConfiguration]
 * exactly like [io.dataloom.governance.audit.InMemoryAuditStore] does, with no
 * change to [DataLoomGovernanceSpec], [DataLoomBuilder], or [DataLoomGovernance]
 * needed: [DataLoomGovernanceSpec.auditStore] is already the plain
 * [io.dataloom.governance.audit.AuditStore] port
 * [DataLoomBuilderGovernanceTest] exercises with a hand-written fake, and
 * [DurableAuditStore] is simply another implementation of that same port
 * (ADR-0016).
 */
class DataLoomBuilderGovernanceDurableAuditTest {

    @Test
    fun theConfiguredAuditLogAppendsThroughADurableAuditStoreAndSurvivesACleanRead() = runTest {
        val backing = FakeAuditChainStateStore()
        val scope = AuditStoreScope("governance-durable-audit-test")
        val durableStore = DurableAuditStore(backing, scope)
        val hmacCalculator = FakeHmacCalculator()

        val governance = assertNotNull(
            builder()
                .governanceConfiguration(
                    DataLoomGovernanceSpec(
                        auditStore = durableStore,
                        auditKey = "key-bytes".encodeToByteArray(),
                        hmacCalculator = hmacCalculator,
                    ),
                )
                .build()
                .governance,
        )

        val auditLog = assertNotNull(governance.auditLog)
        val first = auditLog.append(AuditEvent(TenantId("acme"), PrincipalId("alice"), AuditEventType("access.denied")))
        val second = auditLog.append(AuditEvent(TenantId("acme"), PrincipalId("alice"), AuditEventType("access.allowed")))

        assertEquals(0L, first.sequence)
        assertEquals(1L, second.sequence)

        // Read back through a fresh DurableAuditStore handle over the same
        // backing store -- proves the records genuinely round-tripped through
        // durable persistence, not just an in-process list.
        val reread = DurableAuditStore(backing, scope).readAll()
        assertEquals(listOf(first, second), reread)
        assertIs<AuditVerificationResult.Valid>(AuditChainVerifier(hmacCalculator).verify(reread, "key-bytes".encodeToByteArray()))
    }

    // ---------------------------------------------------------------- fixtures

    /** A fake, non-cryptographic keyed hash: deterministic and key-sensitive, which is all these tests need. */
    private class FakeHmacCalculator : DataLoomHmacCalculator {
        override fun hmac(algorithm: HmacAlgorithm, key: ByteArray, input: ByteArray): DataLoomMac {
            require(key.isNotEmpty()) { "key must not be empty." }
            val size = if (algorithm == HmacAlgorithm.HMAC_SHA_256) 32 else 64
            var state = 1_469_598_103_934_665_603UL
            for (byte in key) {
                state = (state xor byte.toUByte().toULong()) * 1_099_511_628_211UL
            }
            for (byte in input) {
                state = (state xor byte.toUByte().toULong()) * 1_099_511_628_211UL
            }
            val bytes = ByteArray(size)
            for (i in bytes.indices) {
                bytes[i] = ((state shr ((i % 8) * 8)) and 0xFFUL).toByte()
                state *= 1_099_511_628_211UL
            }
            return DataLoomMac(algorithm, bytes)
        }

        override fun verify(key: ByteArray, input: ByteArray, expected: DataLoomMac): Boolean =
            hmac(expected.algorithm, key, input) == expected
    }

    private class FakeAuditChainStateStore : DurableStateStore<AuditStoreScope, AuditChainState> {
        private val states = mutableMapOf<AuditStoreScope, DurableStateRecord<AuditChainState>>()

        override suspend fun load(scope: AuditStoreScope): ProviderOperationResult<DurableStateLoadResult<AuditChainState>> {
            val record = states[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<AuditStoreScope, AuditChainState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<AuditChainState>> {
            val current = states[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            states[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private fun builder(): DataLoomBuilder {
        val transport = StubTransportProvider()
        return DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(StrategyProviderBindings(transportProviderId = transport.descriptor.id))
    }

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("governance-durable-audit-event") },
            queueEntryIds = generator { QueueEntryId("governance-durable-audit-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("governance-durable-audit-queue-lease") },
            conflictIds = generator { ConflictId("governance-durable-audit-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class StubTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("governance-durable-audit-transport"),
            name = ProviderName("Governance Durable Audit Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            error("unused")

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            error("unused")
    }
}
