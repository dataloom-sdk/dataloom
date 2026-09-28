package io.dataloom.governance.policy

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.security.KeyReference
import io.dataloom.governance.requireWellFormedUnicode

/**
 * The unsigned content of one policy pack (ADR-0005, D10): which
 * [PolicyCheckId]s, in which order, an admitted
 * [io.dataloom.api.policy.PolicySet] with id [policySetId] must contain, plus
 * the [version] and signing [keyId] a host uses to govern rollout, rollback,
 * and key rotation across a fleet.
 *
 * A [PolicyPackManifest] names checks; it does not carry
 * [io.dataloom.api.policy.PolicyCheck] implementations. DataLoom does not load
 * or execute code from a pack -- a host resolves each named [PolicyCheckId] to
 * its own real, compiled [io.dataloom.api.policy.PolicyCheck] instance,
 * exactly as it already supplies [io.dataloom.api.plugin.DataLoomPlugin]
 * instances directly rather than DataLoom manufacturing them from a
 * [io.dataloom.api.plugin.PluginManifest]. Verifying a pack (see
 * [PolicyPackVerifier]) proves *which checks a central authority approved, in
 * which order*, not that those implementations behave any particular way.
 *
 * ## Ordering
 *
 * [checkIds] preserves the order a [io.dataloom.api.policy.PolicySet] built
 * from it must evaluate in -- never sorted, matching
 * [io.dataloom.api.policy.PolicySet]'s own "exactly the caller's supplied list
 * order" contract.
 *
 * ## Bounds
 *
 * [checkIds] must be non-empty (an empty pack authorizes nothing, and
 * [io.dataloom.api.policy.PolicySet] itself refuses to be empty) and unique,
 * bounded by [MAXIMUM_CHECKS] -- deliberately the same limit
 * [io.dataloom.api.policy.PolicySet] enforces (that constant is private to its
 * own module, so this is a separate, matching bound rather than a shared
 * reference). [metadata] is bounded by [MAXIMUM_METADATA_ENTRIES] entries of
 * at most [MAXIMUM_METADATA_ENTRY_LENGTH] characters each, the same shape
 * [io.dataloom.governance.audit.AuditEvent.details] already uses for the same
 * reason: an unbounded free-form field inside a signed, distributed artifact
 * is a footgun.
 *
 * ## Determinism across platforms
 *
 * Every string field must be well-formed Unicode (no unpaired surrogates),
 * exactly the constraint [io.dataloom.governance.audit.AuditEvent] already
 * enforces and for the same reason: the JVM and Kotlin/Native encode a lone
 * surrogate to UTF-8 differently, which would make
 * [PolicyPackCanonicalEncoding]'s bytes -- and therefore the pack's signature
 * -- platform-dependent.
 *
 * @param policySetId the [io.dataloom.api.policy.PolicySet] this pack
 *   packages.
 * @param version monotonically increasing, host-assigned content version for
 *   rollout/rollback ordering across a fleet. Must be positive, mirroring
 *   [io.dataloom.api.asset.AssetManifest.version].
 * @param keyId identifies, but never carries, the key material a
 *   [PolicyPackKeyResolver] resolves during verification. DataLoom never
 *   generates, stores, resolves, or rotates the material it names -- see
 *   [KeyReference].
 * @param checkIds the ordered, unique checks this pack authorizes. Defensively
 *   copied.
 * @param metadata optional bounded, non-sensitive context (for example a
 *   change-ticket id or an environment label). Must not contain credentials,
 *   tokens, keys, or personal data -- the same restriction
 *   [io.dataloom.api.context.DataLoomMetadata] itself documents.
 * @throws IllegalArgumentException if [version] is not positive, [checkIds]
 *   is empty, exceeds [MAXIMUM_CHECKS], contains a duplicate, contains a
 *   string that is not well-formed Unicode, or [metadata] violates its own
 *   bounds.
 */
public class PolicyPackManifest(
    public val policySetId: PolicySetId,
    public val version: Long,
    public val keyId: KeyReference,
    checkIds: List<PolicyCheckId>,
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) {
    /** Defensive, order-preserving copy of the supplied check ids. */
    public val checkIds: List<PolicyCheckId> = checkIds.toList()

    init {
        require(version > 0L) { "PolicyPackManifest.version must be positive, but was $version." }
        require(this.checkIds.isNotEmpty()) { "PolicyPackManifest.checkIds must not be empty." }
        require(this.checkIds.size <= MAXIMUM_CHECKS) {
            "PolicyPackManifest.checkIds must not exceed $MAXIMUM_CHECKS entries, but had ${this.checkIds.size}."
        }
        val duplicates = this.checkIds.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "PolicyPackManifest.checkIds must be unique; duplicates: $duplicates." }

        requireWellFormedUnicode("PolicyPackManifest policySetId", policySetId.value)
        requireWellFormedUnicode("PolicyPackManifest keyId", keyId.value)
        for (checkId in this.checkIds) {
            requireWellFormedUnicode("PolicyPackManifest checkId", checkId.value)
        }

        val entries = metadata.entries
        require(entries.size <= MAXIMUM_METADATA_ENTRIES) {
            "PolicyPackManifest.metadata must not exceed $MAXIMUM_METADATA_ENTRIES entries, but had ${entries.size}."
        }
        for ((key, value) in entries) {
            require(key.length <= MAXIMUM_METADATA_ENTRY_LENGTH && value.length <= MAXIMUM_METADATA_ENTRY_LENGTH) {
                "PolicyPackManifest.metadata keys and values must not exceed " +
                    "$MAXIMUM_METADATA_ENTRY_LENGTH characters."
            }
            requireWellFormedUnicode("PolicyPackManifest metadata key", key)
            requireWellFormedUnicode("PolicyPackManifest metadata value", value)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PolicyPackManifest) return false
        return policySetId == other.policySetId &&
            version == other.version &&
            keyId == other.keyId &&
            checkIds == other.checkIds &&
            metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result: Int = policySetId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + keyId.hashCode()
        result = 31 * result + checkIds.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    /** Never renders individual check ids or metadata content. */
    override fun toString(): String =
        "PolicyPackManifest(policySetId=$policySetId, version=$version, keyId=$keyId, checkCount=${checkIds.size})"

    public companion object {
        /** Upper bound on [checkIds], matching [io.dataloom.api.policy.PolicySet]'s own bound. */
        public const val MAXIMUM_CHECKS: Int = 64

        /** Upper bound on the number of [metadata] entries. */
        public const val MAXIMUM_METADATA_ENTRIES: Int = 32

        /** Upper bound on the length of each [metadata] key and value. */
        public const val MAXIMUM_METADATA_ENTRY_LENGTH: Int = 1_024
    }
}
