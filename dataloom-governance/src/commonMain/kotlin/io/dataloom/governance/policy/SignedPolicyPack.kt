package io.dataloom.governance.policy

import io.dataloom.api.security.DataLoomHmacCalculator
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm

/**
 * A [PolicyPackManifest], encoded to its canonical bytes and signed
 * (ADR-0005, D10).
 *
 * [packBytes] is exactly [PolicyPackCanonicalEncoding.encode] of some
 * manifest -- a format version this verifier may or may not still support,
 * which is for [PolicyPackVerifier] to decide, never this type. [mac]
 * authenticates [packBytes] under a host-supplied key via
 * [DataLoomHmacCalculator] -- see [sign]. Constructing a [SignedPolicyPack]
 * performs no verification and no parsing; [PolicyPackVerifier.verify] is the
 * only way to trust one.
 *
 * ## Key handling
 *
 * Signing and verifying both use a caller-supplied key for one call only --
 * exactly [DataLoomHmacCalculator]'s own contract. Neither this type nor
 * [PolicyPackVerifier] stores, generates, rotates, or resolves key material;
 * [PolicyPackKeyResolver] is the host's own lookup, keyed by
 * [PolicyPackManifest.keyId].
 *
 * @param packBytes the canonical, signed bytes. Defensively copied.
 * @param mac the signature over [packBytes]. Must use [MAC_ALGORITHM].
 */
public class SignedPolicyPack(
    packBytes: ByteArray,
    public val mac: DataLoomMac,
) {
    /** Defensive copy of the signed canonical bytes. */
    public val packBytes: ByteArray = packBytes.copyOf()

    init {
        require(mac.algorithm == MAC_ALGORITHM) {
            "SignedPolicyPack mac must use $MAC_ALGORITHM, but was ${mac.algorithm}."
        }
    }

    /** Never renders pack bytes or the signature; both are safe to log but not useful as text. */
    override fun toString(): String = "SignedPolicyPack(byteCount=${packBytes.size})"

    public companion object {
        /** The only MAC algorithm V1 signed policy packs use (ADR-0005, D10). */
        public val MAC_ALGORITHM: HmacAlgorithm = HmacAlgorithm.HMAC_SHA_256

        /**
         * Encodes [manifest] and signs it with [key] via [hmacCalculator].
         *
         * @param key the host-supplied signing key, matching
         *   [manifest]'s declared [PolicyPackManifest.keyId]. Never stored;
         *   used for this call only. Must not be empty.
         * @throws IllegalArgumentException if [key] is empty.
         */
        public fun sign(
            manifest: PolicyPackManifest,
            hmacCalculator: DataLoomHmacCalculator,
            key: ByteArray,
        ): SignedPolicyPack {
            val bytes = PolicyPackCanonicalEncoding.encode(manifest)
            val mac = hmacCalculator.hmac(MAC_ALGORITHM, key, bytes)
            return SignedPolicyPack(bytes, mac)
        }
    }
}
