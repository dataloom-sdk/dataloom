package io.dataloom.governance.policy

import io.dataloom.api.security.DataLoomHmacCalculator
import io.dataloom.api.security.KeyReference
import io.dataloom.governance.CanonicalFormatException

/**
 * Host-owned lookup from a pack's declared [KeyReference] to the raw key
 * bytes to verify it with.
 */
public fun interface PolicyPackKeyResolver {

    /**
     * Returns the key bytes for [keyId], or `null` if this host does not
     * recognize [keyId]. DataLoom never persists, generates, or rotates this
     * material; implementations resolve it from wherever the host already
     * manages keys (a keystore, a KMS, or an in-memory table for tests).
     */
    public fun resolve(keyId: KeyReference): ByteArray?
}

/**
 * Outcome of [PolicyPackVerifier.verify]. Exactly one of these is always
 * returned; verification never throws.
 */
public sealed interface PolicyPackVerificationResult {

    /** [SignedPolicyPack] verified: [manifest] is the trusted, decoded content. */
    public data class Valid(public val manifest: PolicyPackManifest) : PolicyPackVerificationResult

    /**
     * The pack parsed and its declared key was resolved, but the signature
     * does not authenticate under that key. Caused by a modified pack, a
     * modified signature, or a resolved key that does not match what
     * actually signed it -- indistinguishable by design, exactly like
     * [io.dataloom.governance.audit.AuditVerificationFailure.MAC_MISMATCH].
     */
    public data object InvalidSignature : PolicyPackVerificationResult

    /**
     * The pack parsed and named [keyId], but [PolicyPackKeyResolver.resolve]
     * returned `null` (or unusable, empty key material) for it. Distinct from
     * [InvalidSignature]: this host does not have -- or does not yet trust --
     * that key at all, so no signature comparison was even attempted.
     */
    public data class UnknownKeyId(public val keyId: KeyReference) : PolicyPackVerificationResult

    /**
     * [SignedPolicyPack.packBytes] is not a well-formed encoding: truncated,
     * a length prefix that overruns the input, non-canonical UTF-8, trailing
     * bytes, or a manifest that violates [PolicyPackManifest]'s own bounds.
     * [reason] is a short, static category description; it never contains
     * bytes from the input.
     */
    public data class Malformed(public val reason: String) : PolicyPackVerificationResult

    /**
     * The pack parsed enough to read a format-version field, but its value is
     * not one this verifier supports -- including a deliberately downgraded,
     * no-longer-supported version replayed to evade a later format's
     * stricter rules. The pack is rejected without attempting to interpret
     * the remaining bytes under an assumption this verifier cannot justify.
     */
    public data class UnsupportedVersion(public val formatVersion: Int) : PolicyPackVerificationResult
}

/**
 * Verifies a [SignedPolicyPack] before it is ever admitted or evaluated
 * (ADR-0005, D10): HMAC-SHA256 under a host-supplied key, resolved per pack
 * by [PolicyPackKeyResolver] from the pack's own declared
 * [PolicyPackManifest.keyId], using the existing [DataLoomHmacCalculator]
 * primitive.
 *
 * ## Order of checks
 *
 * 1. Decode [SignedPolicyPack.packBytes] into a [PolicyPackManifest]. A
 *    structural failure (wrong domain tag, truncated or oversized fields,
 *    non-canonical UTF-8, trailing bytes, or a manifest that fails its own
 *    validation) is [PolicyPackVerificationResult.Malformed].
 * 2. If decoding instead finds a well-formed but unrecognized format-version
 *    field, this verifier stops with
 *    [PolicyPackVerificationResult.UnsupportedVersion] rather than guessing
 *    how to interpret a layout it does not know.
 * 3. Resolve [PolicyPackManifest.keyId] via [PolicyPackKeyResolver]. A `null`
 *    or empty result is [PolicyPackVerificationResult.UnknownKeyId].
 * 4. Verify the signature over the *entire* [SignedPolicyPack.packBytes] --
 *    never a re-encoding of the decoded manifest -- via
 *    [DataLoomHmacCalculator.verify], which compares in constant time
 *    regardless of where the first differing byte occurs. A mismatch is
 *    [PolicyPackVerificationResult.InvalidSignature].
 *
 * An unverified pack is never evaluated: every non-[PolicyPackVerificationResult.Valid]
 * outcome carries no usable [PolicyPackManifest].
 *
 * ## Key handling
 *
 * [verify] resolves and uses a key for this call only; nothing is retained
 * across calls, and this class holds no key material of its own.
 */
public class PolicyPackVerifier(
    private val hmacCalculator: DataLoomHmacCalculator,
) {

    /** Verifies [pack], resolving its signing key through [keyResolver]. Never throws. */
    public fun verify(pack: SignedPolicyPack, keyResolver: PolicyPackKeyResolver): PolicyPackVerificationResult {
        val manifest = try {
            PolicyPackCanonicalEncoding.decode(pack.packBytes)
        } catch (unsupported: UnsupportedPolicyPackVersionException) {
            return PolicyPackVerificationResult.UnsupportedVersion(unsupported.formatVersion)
        } catch (malformed: CanonicalFormatException) {
            return PolicyPackVerificationResult.Malformed(malformed.message ?: "Malformed policy pack.")
        } catch (malformed: IllegalArgumentException) {
            return PolicyPackVerificationResult.Malformed(malformed.message ?: "Malformed policy pack.")
        }

        val key = keyResolver.resolve(manifest.keyId)
        // An empty key would make DataLoomHmacCalculator.verify throw; treated the same as an
        // unresolved key so this method keeps its non-throwing contract regardless of a
        // misbehaving resolver.
        if (key == null || key.isEmpty()) {
            return PolicyPackVerificationResult.UnknownKeyId(manifest.keyId)
        }

        return if (hmacCalculator.verify(key, pack.packBytes, pack.mac)) {
            PolicyPackVerificationResult.Valid(manifest)
        } else {
            PolicyPackVerificationResult.InvalidSignature
        }
    }
}
