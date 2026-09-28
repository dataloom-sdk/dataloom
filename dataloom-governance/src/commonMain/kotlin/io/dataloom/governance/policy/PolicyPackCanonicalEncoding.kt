package io.dataloom.governance.policy

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.security.KeyReference
import io.dataloom.governance.CanonicalFormatException
import io.dataloom.governance.CanonicalReader
import io.dataloom.governance.CanonicalWriter

/**
 * Thrown internally by [PolicyPackCanonicalEncoding.decode] when it reads a
 * structurally well-formed format-version field that this build does not
 * support. Caught by [PolicyPackVerifier] and reported as
 * [PolicyPackVerificationResult.UnsupportedVersion] rather than
 * [PolicyPackVerificationResult.Malformed]: the input parsed correctly up to
 * that point, and is merely a version this verifier does not (or no longer)
 * understand.
 */
internal class UnsupportedPolicyPackVersionException(val formatVersion: Int) :
    Exception("Unsupported policy pack format version $formatVersion.")

/**
 * Deterministic, unambiguous byte encoding of a [PolicyPackManifest]: exactly
 * the bytes a [SignedPolicyPack]'s signature authenticates.
 *
 * Modeled directly on
 * [io.dataloom.governance.audit.AuditCanonicalEncoding]: all integers
 * big-endian, every variable-length field length-prefixed as a u32, and a
 * domain-tagged, explicitly versioned header, so this encoding can never be
 * confused with the audit chain's and a future format change is a deliberate,
 * detectable decision rather than silent drift.
 *
 * Layout (format version 1):
 *
 * ```
 * string  DOMAIN_TAG ("dataloom.governance.policypack")
 * u32     format version (1)
 * string  keyId
 * string  policySetId
 * u64     version
 * u32     checkId count
 * repeat, IN MANIFEST ORDER (never sorted -- order is evaluation order):
 *   string checkId
 * u32     metadata entry count
 * repeat, ordered by key (UTF-16 code-unit order):
 *   string key
 *   string value
 * ```
 *
 * Strings are canonical UTF-8; [CanonicalReader.string] rejects anything
 * else, and [io.dataloom.governance.requireWellFormedUnicode] keeps lone
 * surrogates out of every field [PolicyPackManifest] accepts, so this
 * encoding is identical on the JVM and Kotlin/Native.
 */
internal object PolicyPackCanonicalEncoding {

    const val DOMAIN_TAG: String = "dataloom.governance.policypack"
    const val SUPPORTED_FORMAT_VERSION: Int = 1

    /**
     * Encodes [manifest] under [formatVersion]. Production callers never pass
     * [formatVersion] explicitly; the parameter exists so tests can construct
     * a validly-shaped but unsupported (or deliberately downgraded) pack --
     * see `PolicyPackVerifierTest`.
     */
    fun encode(manifest: PolicyPackManifest, formatVersion: Int = SUPPORTED_FORMAT_VERSION): ByteArray {
        val writer = CanonicalWriter()
        writer.string(DOMAIN_TAG)
        writer.u32(formatVersion)
        writer.string(manifest.keyId.value)
        writer.string(manifest.policySetId.value)
        writer.u64(manifest.version)
        writer.u32(manifest.checkIds.size)
        for (checkId in manifest.checkIds) {
            writer.string(checkId.value)
        }
        val details = manifest.metadata.entries.entries.sortedBy { it.key }
        writer.u32(details.size)
        for ((key, value) in details) {
            writer.string(key)
            writer.string(value)
        }
        return writer.toByteArray()
    }

    /**
     * Decodes [bytes] into a [PolicyPackManifest], without checking any
     * signature -- decoding alone never establishes trust.
     *
     * @throws UnsupportedPolicyPackVersionException if the declared format
     *   version is not [SUPPORTED_FORMAT_VERSION].
     * @throws CanonicalFormatException if [bytes] is truncated, has a length
     *   prefix that overruns the input, contains non-canonical UTF-8, has
     *   trailing bytes, contains a duplicate metadata key, or does not start
     *   with [DOMAIN_TAG].
     * @throws IllegalArgumentException if the decoded fields are structurally
     *   well-formed but fail [PolicyPackManifest]'s own validation (for
     *   example an empty check-id list).
     */
    fun decode(bytes: ByteArray): PolicyPackManifest {
        val reader = CanonicalReader(bytes)
        if (reader.string() != DOMAIN_TAG) {
            throw CanonicalFormatException("Input is not a policy pack (unexpected domain tag).")
        }
        val formatVersion = reader.u32()
        if (formatVersion != SUPPORTED_FORMAT_VERSION) {
            throw UnsupportedPolicyPackVersionException(formatVersion)
        }

        val keyId = KeyReference(reader.string())
        val policySetId = PolicySetId(reader.string())
        val version = reader.u64()

        val checkCount = reader.u32()
        if (checkCount < 0 || checkCount > PolicyPackManifest.MAXIMUM_CHECKS) {
            throw CanonicalFormatException("Check-id count is out of bounds.")
        }
        val checkIds = ArrayList<PolicyCheckId>(checkCount)
        repeat(checkCount) {
            checkIds += PolicyCheckId(reader.string())
        }

        val metadataCount = reader.u32()
        if (metadataCount < 0 || metadataCount > PolicyPackManifest.MAXIMUM_METADATA_ENTRIES) {
            throw CanonicalFormatException("Metadata entry count is out of bounds.")
        }
        val metadata = LinkedHashMap<String, String>(metadataCount)
        repeat(metadataCount) {
            val key = reader.string()
            val value = reader.string()
            if (metadata.put(key, value) != null) {
                throw CanonicalFormatException("Duplicate metadata key.")
            }
        }

        reader.requireFullyConsumed()

        return PolicyPackManifest(
            policySetId = policySetId,
            version = version,
            keyId = keyId,
            checkIds = checkIds,
            metadata = DataLoomMetadata.of(metadata),
        )
    }
}
