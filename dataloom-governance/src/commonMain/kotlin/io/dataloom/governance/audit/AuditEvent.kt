package io.dataloom.governance.audit

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.TenantId
import io.dataloom.governance.rbac.PrincipalId
import io.dataloom.governance.requireVocabularyIdentifier
import io.dataloom.governance.requireWellFormedUnicode
import kotlin.jvm.JvmInline

/**
 * Identifier of a kind of audited event, for example `access.denied`,
 * `role.bound` or `retry.override`. Same closed charset as
 * [io.dataloom.governance.rbac.ResourceType].
 */
@JvmInline
public value class AuditEventType(
    /** Underlying event-type identifier value. */
    public val value: String,
) {
    init {
        requireVocabularyIdentifier("AuditEventType", value)
    }

    override fun toString(): String = value
}

/**
 * The caller-supplied content of one audit record: who did what in which
 * tenant, plus bounded free-form [details].
 *
 * The timestamp is deliberately not part of the event; [AuditLog] stamps it
 * from its injected clock so a caller cannot backdate a record.
 *
 * ## Redaction
 *
 * [details] must never contain credentials, tokens, keys, personal data, or
 * payload bytes: the audit trail is exported and verified off-device. Its
 * [DataLoomMetadata.toString] does not render values.
 *
 * ## Bounds
 *
 * At most [MAXIMUM_DETAIL_ENTRIES] detail entries; each key and value at most
 * [MAXIMUM_DETAIL_LENGTH] characters. All strings must be well-formed Unicode
 * (no unpaired surrogates) so the canonical encoding that the record MAC
 * covers is identical on every platform.
 */
public data class AuditEvent(
    public val tenantId: TenantId,
    public val principalId: PrincipalId,
    public val eventType: AuditEventType,
    public val details: DataLoomMetadata = DataLoomMetadata.Empty,
) {
    init {
        requireWellFormedUnicode("AuditEvent tenantId", tenantId.value)
        requireWellFormedUnicode("AuditEvent principalId", principalId.value)
        val entries = details.entries
        require(entries.size <= MAXIMUM_DETAIL_ENTRIES) {
            "AuditEvent details must not exceed $MAXIMUM_DETAIL_ENTRIES entries."
        }
        for ((key, value) in entries) {
            require(key.length <= MAXIMUM_DETAIL_LENGTH && value.length <= MAXIMUM_DETAIL_LENGTH) {
                "AuditEvent detail keys and values must not exceed $MAXIMUM_DETAIL_LENGTH characters."
            }
            requireWellFormedUnicode("AuditEvent detail key", key)
            requireWellFormedUnicode("AuditEvent detail value", value)
        }
    }

    public companion object {
        /** Upper bound on the number of [details] entries. */
        public const val MAXIMUM_DETAIL_ENTRIES: Int = 32

        /** Upper bound on the length of each detail key and value. */
        public const val MAXIMUM_DETAIL_LENGTH: Int = 1_024
    }
}
