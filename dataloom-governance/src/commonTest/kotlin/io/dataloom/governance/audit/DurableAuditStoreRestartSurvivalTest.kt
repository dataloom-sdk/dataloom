package io.dataloom.governance.audit

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.governance.SteppingClock
import io.dataloom.governance.platformHmacCalculator
import io.dataloom.governance.rbac.PrincipalId
import io.dataloom.governance.testKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Proves a fresh [DurableAuditStore] instance -- modeling a process restart,
 * since nothing about [DurableAuditStore] is held in memory across calls
 * beyond the [DurableStateStore] it was given -- recovers the exact chain a
 * prior instance wrote, and that the chain still verifies.
 *
 * [durableBacking] plays the role of the durable backend surviving the
 * "restart": it is the one thing that outlives any particular
 * [DurableAuditStore]/[AuditLog] instance in this test, exactly as a real
 * database or file would outlive an in-process object across a real restart.
 */
class DurableAuditStoreRestartSurvivalTest {

    private val scope = AuditStoreScope("restart-survival")

    private fun event(n: Int) = AuditEvent(
        tenantId = TenantId("tenant-one"),
        principalId = PrincipalId("alice"),
        eventType = AuditEventType("access.denied"),
        details = DataLoomMetadata.of(mapOf("n" to n.toString())),
    )

    @Test
    fun aFreshStoreInstanceRecoversTheChainWrittenBeforeRestart() = runTest {
        val durableBacking = PersistentBackingStore()

        val beforeRestart = AuditLog(
            DurableAuditStore(durableBacking, scope),
            platformHmacCalculator(),
            SteppingClock(),
            testKey(),
        )
        val written = (0 until 6).map { beforeRestart.append(event(it)) }

        // Simulate a process restart: brand new DurableAuditStore and AuditLog
        // objects, sharing only the durable backing store.
        val afterRestart = DurableAuditStore(durableBacking, scope)
        assertEquals(written, afterRestart.readAll())
        assertEquals(written.last(), afterRestart.head())

        val verified = AuditChainVerifier(platformHmacCalculator()).verify(afterRestart.readAll(), testKey())
        assertEquals(AuditVerificationResult.Valid(6, written.last().toAnchor(), anchorVerified = false), verified)
    }

    @Test
    fun appendingThroughANewInstanceAfterRestartExtendsTheSameChain() = runTest {
        val durableBacking = PersistentBackingStore()
        val firstProcess = AuditLog(DurableAuditStore(durableBacking, scope), platformHmacCalculator(), SteppingClock(), testKey())
        firstProcess.append(event(0))
        firstProcess.append(event(1))

        // A new "process" picks up where the last one left off.
        val secondProcess = AuditLog(DurableAuditStore(durableBacking, scope), platformHmacCalculator(), SteppingClock(start = 5_000L), testKey())
        val third = secondProcess.append(event(2))

        assertEquals(2L, third.sequence)
        val all = DurableAuditStore(durableBacking, scope).readAll()
        assertEquals(3, all.size)
        assertIs<AuditVerificationResult.Valid>(AuditChainVerifier(platformHmacCalculator()).verify(all, testKey()))
    }

    /** A [DurableStateStore] over [AuditChainState] that outlives any one [DurableAuditStore] handle. */
    private class PersistentBackingStore : DurableStateStore<AuditStoreScope, AuditChainState> {
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
}
