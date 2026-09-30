package io.dataloom.governance.audit

import io.dataloom.api.context.DataLoomMetadata
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
import io.dataloom.governance.SteppingClock
import io.dataloom.governance.platformHmacCalculator
import io.dataloom.governance.rbac.PrincipalId
import io.dataloom.governance.testKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Proves [AuditChainVerifier] catches exactly the same tampering
 * ([AuditTamperDetectionTest]'s own scenarios) when the records under test are
 * sourced through [DurableAuditStore] instead of [InMemoryAuditStore] -- both
 * for tampering applied to the list [DurableAuditStore.readAll] returns
 * (mutation, reordering, truncation -- realistic once records have left the
 * store and are being verified) and for corruption of the raw persisted
 * payload itself (caught earlier, at decode time, by [AuditChainStateCodec]
 * and [AuditChainState]'s own structural invariants -- see [AuditChainState]'s
 * "Invariants" documentation for why a corrupt payload cannot decode into a
 * reordered chain in the first place).
 */
class DurableAuditStoreTamperDetectionTest {

    private val verifier = AuditChainVerifier(platformHmacCalculator())
    private val key = testKey()
    private val scope = AuditStoreScope("tamper-detection")

    private fun event(n: Int) = AuditEvent(
        tenantId = TenantId("tenant-one"),
        principalId = PrincipalId("alice"),
        eventType = AuditEventType("role.bound"),
        details = DataLoomMetadata.of(mapOf("n" to n.toString())),
    )

    private fun flipped(mac: DataLoomMac): DataLoomMac {
        val bytes = mac.copyBytes()
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        return DataLoomMac(mac.algorithm, bytes)
    }

    private suspend fun durablyPersistedChain(length: Int): List<AuditRecord> {
        val store = InMemoryDurableAuditStateStore()
        val log = AuditLog(DurableAuditStore(store, scope), platformHmacCalculator(), SteppingClock(), testKey())
        repeat(length) { log.append(event(it)) }
        // Read back through a *fresh* DurableAuditStore handle: proves the chain
        // survives round-tripping through the durable store, not just the
        // in-process AuditLog that wrote it.
        return DurableAuditStore(store, scope).readAll()
    }

    // -- tampering the records returned from the durable store -------------------

    @Test
    fun modifyingARecordSourcedFromTheDurableStoreIsDetected() = runTest {
        val records = durablyPersistedChain(5).toMutableList()
        records[2] = records[2].copy(event = records[2].event.copy(principalId = PrincipalId("mallory")))
        val result = verifier.verify(records, key)
        assertIs<AuditVerificationResult.Invalid>(result)
        assertEquals(AuditVerificationFailure.MAC_MISMATCH, result.failure)
        assertEquals(2, result.recordIndex)
    }

    @Test
    fun reorderingRecordsSourcedFromTheDurableStoreIsDetected() = runTest {
        val records = durablyPersistedChain(5).toMutableList()
        records[1] = records[2].also { records[2] = records[1] }
        val result = verifier.verify(records, key)
        assertIs<AuditVerificationResult.Invalid>(result)
        assertEquals(AuditVerificationFailure.SEQUENCE_MISMATCH, result.failure)
    }

    @Test
    fun truncatingTheTailOfARecordsSourcedFromTheDurableStoreIsOnlyDetectedWithAnAnchor() = runTest {
        val records = durablyPersistedChain(5)
        val withoutAnchor = verifier.verify(records.take(3), key)
        assertEquals(AuditVerificationResult.Valid(3, records[2].toAnchor(), anchorVerified = false), withoutAnchor)

        val anchor = records.last().toAnchor()
        val withAnchor = verifier.verify(records.take(3), key, anchor)
        assertIs<AuditVerificationResult.Invalid>(withAnchor)
        assertEquals(AuditVerificationFailure.ANCHOR_TRUNCATED, withAnchor.failure)
    }

    @Test
    fun verifyingRecordsSourcedFromTheDurableStoreWithTheWrongKeyFails() = runTest {
        val records = durablyPersistedChain(4)
        val wrong = testKey().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val result = verifier.verify(records, wrong)
        assertIs<AuditVerificationResult.Invalid>(result)
        assertEquals(AuditVerificationFailure.MAC_MISMATCH, result.failure)
        assertEquals(0, result.recordIndex)
    }

    @Test
    fun anIntactChainSourcedFromTheDurableStoreVerifies() = runTest {
        val records = durablyPersistedChain(6)
        val result = verifier.verify(records, key)
        assertEquals(AuditVerificationResult.Valid(6, records.last().toAnchor(), anchorVerified = false), result)
    }

    // -- corruption of the raw persisted payload itself: caught earlier, at decode time --

    @Test
    fun aTruncatedRawPersistedPayloadFailsToDecode() {
        val codec = AuditChainStateCodec()
        val chain = AuditChainState(listOf(sampleRecord(0), sampleRecord(1), sampleRecord(2)))
        val encoded = codec.encode(chain)
        // Drop the tail -- modeling an attacker (or plain corruption) truncating
        // the raw bytes an underlying physical store holds, mid-record.
        val truncated = encoded.dropLast(5)
        assertFailsWith<IllegalArgumentException> { codec.decode(truncated) }
    }

    @Test
    fun aRawPersistedPayloadWithRecordsReorderedFailsToDecode() {
        val codec = AuditChainStateCodec()
        val chain = AuditChainState(listOf(sampleRecord(0), sampleRecord(1), sampleRecord(2)))
        val encoded = codec.encode(chain)
        val fields = encoded.split("|").toMutableList()
        // The last two fields are the encoded record blobs (header|version|count|r0|r1|r2); swap r1 and r2.
        val lastIndex = fields.lastIndex
        fields[lastIndex] = fields[lastIndex - 1].also { fields[lastIndex - 1] = fields[lastIndex] }
        assertFailsWith<IllegalArgumentException> { codec.decode(fields.joinToString("|")) }
    }

    private fun sampleRecord(sequence: Long): AuditRecord = AuditRecord(
        sequence = sequence,
        recordedAt = DataLoomInstant(sequence),
        previousMac = if (sequence == 0L) null else fixedMac(sequence.toInt() - 1),
        event = event(sequence.toInt()),
        mac = fixedMac(sequence.toInt()),
    )

    private fun fixedMac(fill: Int): DataLoomMac = DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32) { fill.toByte() })

    /** A [DurableStateStore] over [AuditChainState] with real compare-and-set semantics, for realistic round-tripping. */
    private class InMemoryDurableAuditStateStore : DurableStateStore<AuditStoreScope, AuditChainState> {
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
