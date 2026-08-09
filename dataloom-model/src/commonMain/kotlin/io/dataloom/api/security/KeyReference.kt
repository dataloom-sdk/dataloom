package io.dataloom.api.security

import kotlin.jvm.JvmInline

/**
 * Opaque label for application-managed key material — for example a
 * platform-keystore alias, a KMS key ID, or an application-defined name.
 *
 * A [KeyReference] never carries raw key bytes and DataLoom never generates,
 * stores, resolves, or rotates the material it names. It is never accepted
 * as a parameter anywhere in [DataLoomHmacCalculator]: an application
 * resolves its own key material (from Android Keystore, iOS Keychain, an
 * external KMS, or wherever it already manages keys) and passes the
 * resulting bytes directly to [DataLoomHmacCalculator]; a [KeyReference] is
 * only for recording, in metadata such as a future encryption manifest,
 * *which* key was used.
 *
 * Ownership: host application — key custody stays entirely outside
 * DataLoom.
 *
 * Deliberately the same flat, single-field shape used throughout
 * `io.dataloom.api.identifier` (`WorkflowId`, `ChangeSetId`, and similar
 * types), rather than a richer structure — no concrete near-term need for
 * extra fields (backend, version) was found.
 *
 * Constraints:
 * - [value] must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - `toString()` returns [value] — safe, because this is a reference, never
 *   the key itself.
 */
@JvmInline
public value class KeyReference(
    /** Underlying opaque key-reference value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "KeyReference must not be blank." }
    }

    override fun toString(): String = value
}
