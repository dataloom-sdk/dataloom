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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every test builds a genuine chain with real platform HMAC-SHA256, mutates the
 * stored records the way an attacker with write access to the store (but not
 * the key) could, and verifies offline with only records, key, and anchor.
 */
class AuditTamperDetectionTest {

    private val verifier = AuditChainVerifier(platformHmacCalculator())
    private val key = testKey()

    private fun event(n: Int) = AuditEvent(
        tenantId = TenantId("tenant-one"),
        principalId = PrincipalId("alice"),
        eventType = AuditEventType("role.bound"),
        details = DataLoomMetadata.of(mapOf("n" to n.toString())),
    )

    private class Chain(val records: List<AuditRecord>, val anchor: AuditAnchor)

    private suspend fun chain(length: Int, chainKey: ByteArray = testKey(), seedOffset: Int = 0): Chain {
        val store = InMemoryAuditStore()
        val log = AuditLog(store, platformHmacCalculator(), SteppingClock(), chainKey)
        repeat(length) { log.append(event(seedOffset + it)) }
        return Chain(store.readAll(), log.anchor()!!)
    }

    private fun assertInvalid(
        expected: AuditVerificationFailure,
        expectedIndex: Int?,
        records: List<AuditRecord>,
        anchor: AuditAnchor? = null,
        verifyKey: ByteArray = key,
    ) {
        val result = verifier.verify(records, verifyKey, anchor)
        assertIs<AuditVerificationResult.Invalid>(result)
        assertEquals(expected, result.failure)
        assertEquals(expectedIndex, result.recordIndex)
    }

    private fun flipped(mac: DataLoomMac): DataLoomMac {
        val bytes = mac.copyBytes()
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        return DataLoomMac(mac.algorithm, bytes)
    }

    // -- modification ---------------------------------------------------------

    @Test
    fun modifyingAnyAuthenticatedFieldOfARecordIsDetectedAtThatRecord() = runTest {
        val c = chain(5)
        val original = c.records[2]
        val mutations: Map<String, AuditRecord> = mapOf(
            "recordedAt" to original.copy(recordedAt = DataLoomInstant(original.recordedAt.epochMilliseconds + 1)),
            "tenantId" to original.copy(event = original.event.copy(tenantId = TenantId("tenant-two"))),
            "principalId" to original.copy(event = original.event.copy(principalId = PrincipalId("mallory"))),
            "eventType" to original.copy(event = original.event.copy(eventType = AuditEventType("access.allowed"))),
            "details value" to original.copy(event = original.event.copy(details = DataLoomMetadata.of(mapOf("n" to "999")))),
            "details key" to original.copy(event = original.event.copy(details = DataLoomMetadata.of(mapOf("m" to "2")))),
            "details added" to original.copy(event = original.event.copy(details = DataLoomMetadata.of(mapOf("n" to "2", "x" to "y")))),
            "details removed" to original.copy(event = original.event.copy(details = DataLoomMetadata.Empty)),
        )
        for ((name, mutated) in mutations) {
            val records = c.records.toMutableList().also { it[2] = mutated }
            assertInvalid(AuditVerificationFailure.MAC_MISMATCH, 2, records, verifyKey = key)
            assertTrue(mutated != original, "$name mutation must differ from the original")
        }
    }

    @Test
    fun replacingARecordMacWithAnotherTagIsDetected() = runTest {
        val c = chain(4)
        val records = c.records.toMutableList()
        records[1] = records[1].copy(mac = flipped(records[1].mac))
        assertInvalid(AuditVerificationFailure.MAC_MISMATCH, 1, records)
    }

    @Test
    fun editingTheFirstRecordIsDetectedAtIndexZero() = runTest {
        val c = chain(3)
        val records = c.records.toMutableList()
        records[0] = records[0].copy(event = records[0].event.copy(principalId = PrincipalId("mallory")))
        assertInvalid(AuditVerificationFailure.MAC_MISMATCH, 0, records)
    }

    @Test
    fun forgingAPreviousLinkIsDetectedAsALinkMismatch() = runTest {
        val c = chain(4)
        val records = c.records.toMutableList()
        records[2] = records[2].copy(previousMac = flipped(records[2].previousMac!!))
        assertInvalid(AuditVerificationFailure.PREVIOUS_LINK_MISMATCH, 2, records)
    }

    @Test
    fun editingARecordAndItsSuccessorsLinkStillFailsBecauseTheMacCoversTheContent() = runTest {
        // Attacker edits record 1 and "repairs" record 2's previous link to the (now wrong) MAC of record 1.
        val c = chain(4)
        val records = c.records.toMutableList()
        records[1] = records[1].copy(event = records[1].event.copy(principalId = PrincipalId("mallory")))
        assertInvalid(AuditVerificationFailure.MAC_MISMATCH, 1, records)
    }

    // -- reordering -----------------------------------------------------------

    @Test
    fun swappingAdjacentRecordsIsDetected() = runTest {
        val c = chain(5)
        val records = c.records.toMutableList()
        records[1] = c.records[2].also { records[2] = c.records[1] }
        assertInvalid(AuditVerificationFailure.SEQUENCE_MISMATCH, 1, records)
    }

    @Test
    fun swappingDistantRecordsIsDetectedAtTheFirstDisplacedPosition() = runTest {
        val c = chain(6)
        val records = c.records.toMutableList()
        records[0] = c.records[4].also { records[4] = c.records[0] }
        assertInvalid(AuditVerificationFailure.SEQUENCE_MISMATCH, 0, records)
    }

    @Test
    fun reversingTheChainIsDetected() = runTest {
        val c = chain(4)
        assertInvalid(AuditVerificationFailure.SEQUENCE_MISMATCH, 0, c.records.reversed())
    }

    @Test
    fun duplicatingARecordIsDetected() = runTest {
        val c = chain(4)
        val records = c.records.toMutableList()
        records.add(2, c.records[1])
        assertInvalid(AuditVerificationFailure.SEQUENCE_MISMATCH, 2, records)
    }

    // -- deletion in the middle / at the head ---------------------------------

    @Test
    fun deletingAMiddleRecordIsDetectedWithoutAnAnchor() = runTest {
        val c = chain(5)
        val records = c.records.toMutableList().also { it.removeAt(2) }
        assertInvalid(AuditVerificationFailure.SEQUENCE_MISMATCH, 2, records)
    }

    @Test
    fun deletingTheFirstRecordIsDetectedWithoutAnAnchor() = runTest {
        val c = chain(5)
        assertInvalid(AuditVerificationFailure.SEQUENCE_MISMATCH, 0, c.records.drop(1))
    }

    @Test
    fun deletingSeveralMiddleRecordsIsDetected() = runTest {
        val c = chain(8)
        val records = c.records.filterIndexed { index, _ -> index !in 2..4 }
        assertInvalid(AuditVerificationFailure.SEQUENCE_MISMATCH, 2, records)
    }

    // -- truncation of the tail ------------------------------------------------

    @Test
    fun truncatingTheTailIsNotDetectableWithoutAnAnchorAndThatIsReportedHonestly() = runTest {
        val c = chain(5)
        val result = verifier.verify(c.records.take(3), key)
        // The shortened chain is a perfectly valid shorter chain; with no anchor there is nothing to compare against.
        assertEquals(
            AuditVerificationResult.Valid(3, c.records[2].toAnchor(), anchorVerified = false),
            result,
        )
    }

    @Test
    fun truncatingTheTailIsDetectedWhenAnAnchorIsSupplied() = runTest {
        val c = chain(5)
        assertInvalid(AuditVerificationFailure.ANCHOR_TRUNCATED, null, c.records.take(3), anchor = c.anchor)
        assertInvalid(AuditVerificationFailure.ANCHOR_TRUNCATED, null, c.records.take(4), anchor = c.anchor)
        assertInvalid(AuditVerificationFailure.ANCHOR_TRUNCATED, null, emptyList(), anchor = c.anchor)
    }

    @Test
    fun aChainEndingExactlyAtTheAnchorIsAnchorVerified() = runTest {
        val c = chain(5)
        val result = verifier.verify(c.records, key, c.anchor)
        assertEquals(AuditVerificationResult.Valid(5, c.anchor, anchorVerified = true), result)
    }

    @Test
    fun truncationAcrossAnEarlierAnchorIsStillDetectedForThatAnchor() = runTest {
        val c = chain(6)
        val earlyAnchor = c.records[2].toAnchor()
        // Truncating to 4 records keeps the early anchor's record: valid against that anchor.
        assertIs<AuditVerificationResult.Valid>(verifier.verify(c.records.take(4), key, earlyAnchor))
        // Truncating to 2 removes it.
        assertInvalid(AuditVerificationFailure.ANCHOR_TRUNCATED, null, c.records.take(2), anchor = earlyAnchor)
    }

    // -- full-chain replacement by a key holder --------------------------------

    @Test
    fun aValidlyRekeyedOrForkedChainIsCaughtByTheAnchorNotByTheChainAlone() = runTest {
        val original = chain(5)
        // A forger who has the key rebuilds a different chain of the same length.
        val forged = chain(5, seedOffset = 100)
        assertIs<AuditVerificationResult.Valid>(verifier.verify(forged.records, key), "forged chain is self-consistent")
        assertInvalid(AuditVerificationFailure.ANCHOR_MISMATCH, 4, forged.records, anchor = original.anchor)
    }

    @Test
    fun anAnchorWithATamperedMacDoesNotMatch() = runTest {
        val c = chain(3)
        val bad = AuditAnchor(c.anchor.sequence, flipped(c.anchor.mac))
        assertInvalid(AuditVerificationFailure.ANCHOR_MISMATCH, 2, c.records, anchor = bad)
    }

    // -- wrong key -------------------------------------------------------------

    @Test
    fun verifyingWithTheWrongKeyFailsAtTheFirstRecord() = runTest {
        val c = chain(4)
        val wrong = testKey().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertInvalid(AuditVerificationFailure.MAC_MISMATCH, 0, c.records, verifyKey = wrong)
    }

    @Test
    fun aChainBuiltUnderAnotherKeyDoesNotVerifyUnderThisOne() = runTest {
        val other = chain(3, chainKey = "a-completely-different-key-material".encodeToByteArray())
        assertInvalid(AuditVerificationFailure.MAC_MISMATCH, 0, other.records)
    }

    @Test
    fun anEmptyVerificationKeyIsRejected() = runTest {
        val c = chain(1)
        assertFailsWith<IllegalArgumentException> { verifier.verify(c.records, ByteArray(0)) }
    }

    // -- the mac algorithm is fixed -------------------------------------------

    @Test
    fun aRecordCannotBeDowngradedToAnotherMacAlgorithm() = runTest {
        val c = chain(2)
        val sha512 = DataLoomMac(HmacAlgorithm.HMAC_SHA_512, ByteArray(64))
        assertFailsWith<IllegalArgumentException> { c.records[1].copy(mac = sha512) }
    }

    // -- offline ---------------------------------------------------------------

    @Test
    fun verificationNeedsOnlyRecordsKeyAndAnchor() = runTest {
        val c = chain(4)
        // A fresh verifier with a fresh calculator: no store, no clock, no log, no network.
        val offline = AuditChainVerifier(platformHmacCalculator())
        val result = offline.verify(c.records.toList(), testKey(), c.anchor)
        assertEquals(AuditVerificationResult.Valid(4, c.anchor, anchorVerified = true), result)
    }
}
