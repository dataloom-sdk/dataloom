package io.dataloom.governance.policy

import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.security.KeyReference
import io.dataloom.governance.platformHmacCalculator
import io.dataloom.governance.testKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every test signs a genuine pack with the real platform HMAC-SHA256
 * (ADR-0005, D10), then verifies it the way an attacker or a misconfigured
 * host could present it, using only [SignedPolicyPack] and a
 * [PolicyPackKeyResolver] -- never a store, never the signer's own state.
 */
class PolicyPackVerifierTest {

    private val verifier = PolicyPackVerifier(platformHmacCalculator())
    private val realKeyId = KeyReference("key-1")
    private val realKey = testKey()

    private fun manifest(
        checkIds: List<PolicyCheckId> = listOf(PolicyCheckId("c1")),
        version: Long = 1L,
        keyId: KeyReference = realKeyId,
    ) = PolicyPackManifest(PolicySetId("set"), version, keyId, checkIds)

    private fun sign(manifest: PolicyPackManifest = manifest(), key: ByteArray = realKey): SignedPolicyPack =
        SignedPolicyPack.sign(manifest, platformHmacCalculator(), key)

    private fun resolverOf(vararg pairs: Pair<KeyReference, ByteArray>): PolicyPackKeyResolver =
        PolicyPackKeyResolver { keyId -> pairs.toMap()[keyId] }

    private val realResolver = resolverOf(realKeyId to realKey)

    private fun flipped(mac: DataLoomMac): DataLoomMac {
        val bytes = mac.copyBytes()
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        return DataLoomMac(mac.algorithm, bytes)
    }

    // -- valid -------------------------------------------------------------------

    @Test
    fun aGenuinelySignedPackVerifies() {
        val pack = sign()
        val result = assertIs<PolicyPackVerificationResult.Valid>(verifier.verify(pack, realResolver))
        assertEquals(manifest(), result.manifest)
    }

    @Test
    fun checkOrderAndMultipleChecksSurviveVerification() {
        val ordered = listOf(PolicyCheckId("z"), PolicyCheckId("a"), PolicyCheckId("m"))
        val pack = sign(manifest(checkIds = ordered))
        val result = assertIs<PolicyPackVerificationResult.Valid>(verifier.verify(pack, realResolver))
        assertEquals(ordered, result.manifest.checkIds)
    }

    // -- modified byte -------------------------------------------------------------

    @Test
    fun modifyingAnyByteOfThePackBytesInvalidatesTheSignature() {
        val pack = sign()
        for (index in pack.packBytes.indices) {
            val mutated = pack.packBytes.copyOf()
            mutated[index] = (mutated[index].toInt() xor 0x01).toByte()
            val result = verifier.verify(SignedPolicyPack(mutated, pack.mac), realResolver)
            // A flipped byte usually still parses (most bytes are inside string/int fields that
            // tolerate any byte value) and fails the signature; occasionally it corrupts a length
            // prefix or the format-version field and is instead rejected as malformed or an
            // unsupported version. Either way it must never be reported Valid.
            assertTrue(result !is PolicyPackVerificationResult.Valid, "byte $index must not verify as valid")
        }
    }

    @Test
    fun modifyingTheSignatureItselfIsDetected() {
        val pack = sign()
        val result = verifier.verify(SignedPolicyPack(pack.packBytes, flipped(pack.mac)), realResolver)
        assertEquals(PolicyPackVerificationResult.InvalidSignature, result)
    }

    // -- wrong key -------------------------------------------------------------------

    @Test
    fun verifyingWithTheWrongKeyMaterialForTheSameKeyIdFails() {
        val wrongKey = testKey().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val pack = sign()
        val result = verifier.verify(pack, resolverOf(realKeyId to wrongKey))
        assertEquals(PolicyPackVerificationResult.InvalidSignature, result)
    }

    @Test
    fun aPackSignedUnderOneKeyDoesNotVerifyUnderAnother() {
        val otherKey = "a-completely-different-key-material".encodeToByteArray()
        val pack = sign(key = otherKey)
        val result = verifier.verify(pack, realResolver)
        assertEquals(PolicyPackVerificationResult.InvalidSignature, result)
    }

    // -- unknown key id ------------------------------------------------------------

    @Test
    fun aKeyIdTheResolverDoesNotRecognizeIsReportedDistinctlyFromABadSignature() {
        val pack = sign(manifest(keyId = KeyReference("unknown-key")))
        val result = verifier.verify(pack, realResolver)
        assertEquals(PolicyPackVerificationResult.UnknownKeyId(KeyReference("unknown-key")), result)
    }

    @Test
    fun aResolverReturningEmptyKeyMaterialIsTreatedAsUnknownRatherThanThrowing() {
        val pack = sign()
        val result = verifier.verify(pack, resolverOf(realKeyId to ByteArray(0)))
        assertEquals(PolicyPackVerificationResult.UnknownKeyId(realKeyId), result)
    }

    // -- truncated pack --------------------------------------------------------------

    @Test
    fun aTruncatedPackIsMalformedNotValid() {
        val pack = sign()
        val truncated = SignedPolicyPack(pack.packBytes.copyOfRange(0, pack.packBytes.size - 5), pack.mac)
        val result = verifier.verify(truncated, realResolver)
        assertIs<PolicyPackVerificationResult.Malformed>(result)
    }

    @Test
    fun aPackTruncatedToNothingButTheDomainTagIsMalformed() {
        val pack = sign()
        val truncated = SignedPolicyPack(pack.packBytes.copyOfRange(0, 10), pack.mac)
        assertIs<PolicyPackVerificationResult.Malformed>(verifier.verify(truncated, realResolver))
    }

    // -- empty pack ------------------------------------------------------------------

    @Test
    fun anEmptyPackIsMalformed() {
        val pack = SignedPolicyPack(ByteArray(0), DataLoomMac(HmacAlgorithm.HMAC_SHA_256, ByteArray(32)))
        assertIs<PolicyPackVerificationResult.Malformed>(verifier.verify(pack, realResolver))
    }

    // -- downgrade to an older version -------------------------------------------------

    @Test
    fun aValidlySignedButDowngradedFormatVersionIsRejectedEvenThoughTheSignatureWouldOtherwiseVerify() {
        // A forger who legitimately holds the real key re-signs a pack encoded under an older
        // (here: hypothetical, no-longer/not-yet-supported) format version, hoping the verifier
        // will still accept it. It must not: an unrecognized version is refused before any
        // signature is even attempted.
        val downgradedBytes = PolicyPackCanonicalEncoding.encode(manifest(), formatVersion = 0)
        val mac = platformHmacCalculator().hmac(SignedPolicyPack.MAC_ALGORITHM, realKey, downgradedBytes)
        val pack = SignedPolicyPack(downgradedBytes, mac)

        val result = verifier.verify(pack, realResolver)
        assertEquals(PolicyPackVerificationResult.UnsupportedVersion(0), result)
    }

    @Test
    fun aFutureUnrecognizedFormatVersionIsAlsoRejected() {
        val futureBytes = PolicyPackCanonicalEncoding.encode(manifest(), formatVersion = 2)
        val mac = platformHmacCalculator().hmac(SignedPolicyPack.MAC_ALGORITHM, realKey, futureBytes)
        val pack = SignedPolicyPack(futureBytes, mac)

        assertEquals(PolicyPackVerificationResult.UnsupportedVersion(2), verifier.verify(pack, realResolver))
    }

    // -- offline / no shared state -----------------------------------------------------

    @Test
    fun verificationNeedsOnlyThePackAndAKeyResolver() {
        val pack = sign()
        // A fresh verifier with a fresh calculator: no store, no clock, no signer, no network.
        val offline = PolicyPackVerifier(platformHmacCalculator())
        assertIs<PolicyPackVerificationResult.Valid>(offline.verify(pack, realResolver))
    }
}
