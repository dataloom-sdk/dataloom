package io.dataloom.governance.audit

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.governance.SteppingClock
import io.dataloom.governance.platformHmacCalculator
import io.dataloom.governance.rbac.PrincipalId
import io.dataloom.governance.testKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditLogTest {

    private fun event(n: Int) = AuditEvent(
        tenantId = TenantId("tenant-one"),
        principalId = PrincipalId("alice"),
        eventType = AuditEventType("access.denied"),
        details = DataLoomMetadata.of(mapOf("n" to n.toString())),
    )

    private fun newLog(
        store: AuditStore = InMemoryAuditStore(),
        key: ByteArray = testKey(),
        clock: SteppingClock = SteppingClock(),
    ) = AuditLog(store, platformHmacCalculator(), clock, key)

    @Test
    fun firstRecordIsSequenceZeroWithNoPreviousMac() = runTest {
        val record = newLog().append(event(0))
        assertEquals(0L, record.sequence)
        assertNull(record.previousMac)
        assertEquals(HmacAlgorithm.HMAC_SHA_256, record.mac.algorithm)
    }

    @Test
    fun eachRecordCommitsToThePreviousMac() = runTest {
        val log = newLog()
        val records = (0 until 5).map { log.append(event(it)) }
        for (i in 1 until records.size) {
            assertEquals(i.toLong(), records[i].sequence)
            assertEquals(records[i - 1].mac, records[i].previousMac)
        }
        assertEquals(5, records.map { it.mac }.toSet().size, "every MAC is distinct")
    }

    @Test
    fun recordsAreStampedFromTheInjectedClock() = runTest {
        val log = newLog(clock = SteppingClock(start = 5_000L, step = 7L))
        assertEquals(DataLoomInstant(5_000L), log.append(event(0)).recordedAt)
        assertEquals(DataLoomInstant(5_007L), log.append(event(1)).recordedAt)
    }

    @Test
    fun anEmptyLogVerifiesAsValidWithNoHead() = runTest {
        val log = newLog()
        assertEquals(AuditVerificationResult.Valid(recordCount = 0, head = null, anchorVerified = false), log.verify())
        assertNull(log.anchor())
    }

    @Test
    fun anIntactChainVerifiesAndReportsItsHead() = runTest {
        val log = newLog()
        val records = (0 until 5).map { log.append(event(it)) }
        val result = log.verify()
        assertEquals(AuditVerificationResult.Valid(5, records.last().toAnchor(), anchorVerified = false), result)
        assertEquals(records.last().toAnchor(), log.anchor())
    }

    @Test
    fun verifyingAgainstThePresentHeadAnchorIsAnchorVerified() = runTest {
        val log = newLog()
        repeat(3) { log.append(event(it)) }
        val result = log.verify(log.anchor())
        assertIs<AuditVerificationResult.Valid>(result)
        assertTrue(result.anchorVerified)
    }

    @Test
    fun aChainThatGrewPastTheAnchorStillVerifiesAgainstIt() = runTest {
        val log = newLog()
        repeat(3) { log.append(event(it)) }
        val earlierAnchor = log.anchor()
        repeat(4) { log.append(event(10 + it)) }
        val result = log.verify(earlierAnchor)
        assertIs<AuditVerificationResult.Valid>(result)
        assertEquals(7, result.recordCount)
        assertTrue(result.anchorVerified)
    }

    @Test
    fun concurrentAppendsThroughOneLogProduceOneUnbrokenChain() = runTest {
        val log = newLog()
        (0 until 50).map { n -> async { log.append(event(n)) } }.awaitAll()
        val result = log.verify()
        assertIs<AuditVerificationResult.Valid>(result)
        assertEquals(50, result.recordCount)
    }

    @Test
    fun aFullStoreRefusesTheAppendAndRecordsNothing() = runTest {
        val store = InMemoryAuditStore(capacity = 2)
        val log = newLog(store)
        log.append(event(0))
        log.append(event(1))
        val rejected = assertFailsWith<AuditAppendRejectedException> { log.append(event(2)) }
        assertEquals(AuditAppendRejection.CAPACITY_EXCEEDED, rejected.rejection)
        assertEquals(2, store.readAll().size)
        assertIs<AuditVerificationResult.Valid>(log.verify())
    }

    @Test
    fun aRecordThatDoesNotExtendTheHeadIsRefusedByTheStore() = runTest {
        val store = InMemoryAuditStore()
        val log = newLog(store)
        val first = log.append(event(0))
        log.append(event(1))

        // Re-appending an old record, or one with the wrong previous link, is a head conflict.
        val stale = assertFailsWith<AuditAppendRejectedException> { store.append(first) }
        assertEquals(AuditAppendRejection.HEAD_CONFLICT, stale.rejection)

        val head = store.head()!!
        val wrongLink = AuditRecord(
            sequence = head.sequence + 1,
            recordedAt = DataLoomInstant(1L),
            previousMac = first.mac,
            event = event(2),
            mac = head.mac,
        )
        val conflict = assertFailsWith<AuditAppendRejectedException> { store.append(wrongLink) }
        assertEquals(AuditAppendRejection.HEAD_CONFLICT, conflict.rejection)
        assertEquals(2, store.readAll().size)
    }

    @Test
    fun anEmptyStoreOnlyAcceptsASequenceZeroRecord() = runTest {
        val store = InMemoryAuditStore()
        val secondRecord = newLog(InMemoryAuditStore()).also { it.append(event(0)) }.append(event(1))
        val rejected = assertFailsWith<AuditAppendRejectedException> { store.append(secondRecord) }
        assertEquals(AuditAppendRejection.HEAD_CONFLICT, rejected.rejection)
        assertNull(store.head())
    }

    @Test
    fun theLogDefensivelyCopiesItsKey() = runTest {
        val store = InMemoryAuditStore()
        val callerKey = testKey()
        val log = newLog(store, key = callerKey)
        callerKey.fill(0)
        log.append(event(0))
        log.append(event(1))
        // The log kept its own copy: an independent verifier with the original key value accepts the chain.
        val independent = AuditChainVerifier(platformHmacCalculator()).verify(store.readAll(), testKey())
        assertEquals(2, (independent as AuditVerificationResult.Valid).recordCount)
    }

    @Test
    fun anEmptyKeyIsRejected() {
        assertFailsWith<IllegalArgumentException> { newLog(key = ByteArray(0)) }
    }

    @Test
    fun toStringDoesNotRenderKeyOrRecords() = runTest {
        val log = newLog()
        log.append(event(0))
        val rendered = log.toString() + InMemoryAuditStore().toString()
        assertFalse(rendered.contains("0123456789abcdef"))
        assertFalse(rendered.contains("alice"))
    }

    @Test
    fun inMemoryStoreRejectsANonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { InMemoryAuditStore(capacity = 0) }
    }

    @Test
    fun inMemoryStoreReadAllIsASnapshot() = runTest {
        val store = InMemoryAuditStore()
        val log = newLog(store)
        log.append(event(0))
        val snapshot = store.readAll()
        log.append(event(1))
        assertEquals(1, snapshot.size)
        assertEquals(2, store.readAll().size)
    }

    // -- AuditRecord / AuditAnchor invariants ---------------------------------

    private fun mac(fill: Int, algorithm: HmacAlgorithm = HmacAlgorithm.HMAC_SHA_256): DataLoomMac =
        DataLoomMac(algorithm, ByteArray(if (algorithm == HmacAlgorithm.HMAC_SHA_256) 32 else 64) { fill.toByte() })

    @Test
    fun recordInvariantsAreEnforcedAtConstruction() {
        val ev = event(0)
        val at = DataLoomInstant(1L)
        AuditRecord(0L, at, null, ev, mac(1))
        AuditRecord(1L, at, mac(1), ev, mac(2))
        assertFailsWith<IllegalArgumentException>("negative sequence") { AuditRecord(-1L, at, null, ev, mac(1)) }
        assertFailsWith<IllegalArgumentException>("first record with previous") { AuditRecord(0L, at, mac(1), ev, mac(2)) }
        assertFailsWith<IllegalArgumentException>("later record without previous") { AuditRecord(1L, at, null, ev, mac(2)) }
        assertFailsWith<IllegalArgumentException>("wrong mac algorithm") {
            AuditRecord(0L, at, null, ev, mac(1, HmacAlgorithm.HMAC_SHA_512))
        }
        assertFailsWith<IllegalArgumentException>("wrong previous algorithm") {
            AuditRecord(1L, at, mac(1, HmacAlgorithm.HMAC_SHA_512), ev, mac(2))
        }
    }

    @Test
    fun anchorInvariantsAreEnforcedAtConstruction() {
        AuditAnchor(0L, mac(1))
        assertFailsWith<IllegalArgumentException> { AuditAnchor(-1L, mac(1)) }
        assertFailsWith<IllegalArgumentException> { AuditAnchor(0L, mac(1, HmacAlgorithm.HMAC_SHA_512)) }
    }

    @Test
    fun theEventIsPartOfWhatTheMacCovers() = runTest {
        val a = newLog().append(event(0))
        val b = newLog().append(event(1))
        assertNotEquals(a.mac, b.mac)
    }
}
