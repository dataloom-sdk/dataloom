package io.dataloom.governance.audit

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.governance.rbac.PrincipalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest

/**
 * Verifies [DurableAuditStore]'s compare-and-set append semantics: it extends
 * the durably-persisted head exactly like [InMemoryAuditStore] extends its
 * in-memory one, never loses an update or lets two appenders claim the same
 * chain position, and surfaces underlying store failures distinctly from
 * chain-integrity rejections.
 */
class DurableAuditStoreTest {

    private val scope = AuditStoreScope("test-scope")

    private fun event(n: Int) = AuditEvent(
        tenantId = TenantId("tenant-one"),
        principalId = PrincipalId("alice"),
        eventType = AuditEventType("access.denied"),
        details = DataLoomMetadata.of(mapOf("n" to n.toString())),
    )

    private fun mac(fill: Int): DataLoomMac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { fill.toByte() })

    /** A structurally valid record at [sequence], linking to [mac] of `sequence - 1`. */
    private fun record(sequence: Long): AuditRecord = AuditRecord(
        sequence = sequence,
        recordedAt = DataLoomInstant(sequence),
        previousMac = if (sequence == 0L) null else mac(sequence.toInt() - 1),
        event = event(sequence.toInt()),
        mac = mac(sequence.toInt()),
    )

    // -- basic append/read ------------------------------------------------------

    @Test
    fun headAndReadAllAreEmptyBeforeAnyAppend() = runTest {
        val durable = DurableAuditStore(FakeAuditChainStore(), scope)
        assertNull(durable.head())
        assertEquals(emptyList(), durable.readAll())
    }

    @Test
    fun appendingTheFirstRecordSucceeds() = runTest {
        val durable = DurableAuditStore(FakeAuditChainStore(), scope)
        durable.append(record(0))
        assertEquals(record(0), durable.head())
        assertEquals(listOf(record(0)), durable.readAll())
    }

    @Test
    fun appendingASequenceOfCorrectlyLinkedRecordsSucceeds() = runTest {
        val durable = DurableAuditStore(FakeAuditChainStore(), scope)
        (0L until 10L).forEach { durable.append(record(it)) }
        val all = durable.readAll()
        assertEquals(10, all.size)
        assertEquals((0L until 10L).map { it }, all.map { it.sequence })
        assertEquals(record(9), durable.head())
    }

    // -- table-driven: what extends the head and what does not -----------------

    private class HeadConflictCase(val name: String, val existing: List<AuditRecord>, val attempted: AuditRecord)

    @Test
    fun recordsThatDoNotExtendTheHeadAreRejectedAsHeadConflict() = runTest {
        val cases = listOf(
            HeadConflictCase("sequence 1 on an empty store", emptyList(), record(1)),
            HeadConflictCase("sequence 0 replayed against a store that already advanced", listOf(record(0), record(1)), record(0)),
            HeadConflictCase("repeating sequence 0 after it is committed", listOf(record(0)), record(0)),
            HeadConflictCase("skipping ahead to sequence 2", listOf(record(0)), record(2)),
            HeadConflictCase("correct sequence but wrong previous mac", listOf(record(0)), record(1).copy(previousMac = mac(77))),
        )
        for (case in cases) {
            val store = FakeAuditChainStore()
            case.existing.forEach { store.seed(scope, it) }
            val durable = DurableAuditStore(store, scope)
            val rejected = assertFailsWith<AuditAppendRejectedException>(case.name) { durable.append(case.attempted) }
            assertEquals(AuditAppendRejection.HEAD_CONFLICT, rejected.rejection, case.name)
            // Nothing was persisted by the rejected attempt.
            assertEquals(case.existing, durable.readAll(), case.name)
        }
    }

    @Test
    fun aConcurrentWriterLandingBetweenLoadAndCompareAndSetIsAHeadConflictNotALostUpdate() = runTest {
        // Simulates a second process's append landing in the window between this
        // call's load and its own compare-and-set: the store reports Conflict.
        val store = RaceLosingStore(winner = record(0))
        val durable = DurableAuditStore(store, scope)
        val rejected = assertFailsWith<AuditAppendRejectedException> { durable.append(record(0).copy(mac = mac(123))) }
        assertEquals(AuditAppendRejection.HEAD_CONFLICT, rejected.rejection)
    }

    // -- capacity ----------------------------------------------------------------

    @Test
    fun reachingTheBoundedCapacityRefusesFurtherAppends() = runTest {
        val store = FakeAuditChainStore()
        repeat(AuditChainState.MAX_RECORD_COUNT) { store.seed(scope, record(it.toLong())) }
        val durable = DurableAuditStore(store, scope)
        val rejected = assertFailsWith<AuditAppendRejectedException> {
            durable.append(record(AuditChainState.MAX_RECORD_COUNT.toLong()))
        }
        assertEquals(AuditAppendRejection.CAPACITY_EXCEEDED, rejected.rejection)
        assertEquals(AuditChainState.MAX_RECORD_COUNT, durable.readAll().size)
    }

    // -- underlying store failures are distinct from chain-integrity rejections --

    @Test
    fun aFailedLoadSurfacesAsAPersistenceExceptionNotAChainRejection() = runTest {
        val durable = DurableAuditStore(FailingLoadStore(), scope)
        val thrown = assertFailsWith<AuditStorePersistenceException> { durable.append(record(0)) }
        assertEquals("TEST_FAILURE", thrown.error.code.value)
    }

    @Test
    fun aFailedCompareAndSetSurfacesAsAPersistenceException() = runTest {
        val durable = DurableAuditStore(FailingCompareAndSetStore(), scope)
        assertFailsWith<AuditStorePersistenceException> { durable.append(record(0)) }
    }

    @Test
    fun headAndReadAllAlsoSurfaceAFailedLoadAsAPersistenceException() = runTest {
        val durable = DurableAuditStore(FailingLoadStore(), scope)
        assertFailsWith<AuditStorePersistenceException> { durable.head() }
        assertFailsWith<AuditStorePersistenceException> { durable.readAll() }
    }

    // -- genuine concurrent appenders (real coroutine interleaving) --------------

    @Test
    fun concurrentAppendersAcrossSeparateInstancesNeverLoseAnUpdateOrDuplicateAPosition() = runTest {
        val store = FakeAuditChainStore()
        val appenderCount = 30

        // Each "appender" models a separate process: its own DurableAuditStore
        // handle, retrying with a freshly computed record whenever it loses the
        // race -- exactly the recovery this class's documentation prescribes for
        // the caller (AuditLog.append) to perform.
        suspend fun appendWithRetry(tag: Int) {
            while (true) {
                val durable = DurableAuditStore(store, scope)
                val head = durable.head()
                yield() // let other concurrently-launched appenders interleave here
                val next = AuditRecord(
                    sequence = (head?.sequence ?: -1L) + 1L,
                    recordedAt = DataLoomInstant(tag.toLong()),
                    previousMac = head?.mac,
                    event = event(tag),
                    mac = mac(1_000 + tag),
                )
                try {
                    durable.append(next)
                    return
                } catch (rejected: AuditAppendRejectedException) {
                    if (rejected.rejection != AuditAppendRejection.HEAD_CONFLICT) throw rejected
                    yield()
                    // lost the race; reload the new head and retry
                }
            }
        }

        (0 until appenderCount).map { tag -> async { appendWithRetry(tag) } }.awaitAll()

        val finalChain = DurableAuditStore(store, scope).readAll()
        assertEquals(appenderCount, finalChain.size)
        assertEquals((0 until appenderCount).map { it.toLong() }, finalChain.map { it.sequence })
        for (i in 1 until finalChain.size) {
            assertEquals(finalChain[i - 1].mac, finalChain[i].previousMac, "chain must be unbroken at position $i")
        }
        // No two appenders claimed the same position with different content.
        assertEquals(appenderCount, finalChain.map { it.mac }.toSet().size)
    }

    // -- test doubles -------------------------------------------------------------

    private class FakeAuditChainStore : DurableStateStore<AuditStoreScope, AuditChainState> {
        private val states = mutableMapOf<AuditStoreScope, DurableStateRecord<AuditChainState>>()

        /** Seeds [scope] with [record] appended to whatever it currently holds, bypassing any store contract. */
        fun seed(scope: AuditStoreScope, record: AuditRecord) {
            val current = states[scope]
            val nextState = AuditChainState((current?.state?.records ?: emptyList()) + record)
            states[scope] = DurableStateRecord(nextState, version = (current?.version ?: -1L) + 1L, schemaVersion = 1)
        }

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

    /** Reports [winner] already committed on the very first [compareAndSet] call, simulating a lost race. */
    private class RaceLosingStore(
        private val winner: AuditRecord,
    ) : DurableStateStore<AuditStoreScope, AuditChainState> {
        override suspend fun load(scope: AuditStoreScope): ProviderOperationResult<DurableStateLoadResult<AuditChainState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<AuditStoreScope, AuditChainState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<AuditChainState>> =
            ProviderOperationResult.Success(
                DurableStateCompareAndSetResult.Conflict(DurableStateRecord(AuditChainState(listOf(winner)), 0L, 1)),
            )
    }

    private class FailingLoadStore : DurableStateStore<AuditStoreScope, AuditChainState> {
        override suspend fun load(scope: AuditStoreScope): ProviderOperationResult<DurableStateLoadResult<AuditChainState>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<AuditStoreScope, AuditChainState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<AuditChainState>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<AuditStoreScope, AuditChainState> {
        override suspend fun load(scope: AuditStoreScope): ProviderOperationResult<DurableStateLoadResult<AuditChainState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<AuditStoreScope, AuditChainState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<AuditChainState>> =
            ProviderOperationResult.Failure(testError())
    }
}

private fun testError(): DataLoomError = DurableAuditStoreTestError(
    code = ErrorCode("TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableAuditStoreTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
