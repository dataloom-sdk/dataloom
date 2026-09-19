package io.dataloom.governance.rbac

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.policy.PolicyCheckOutcome

/**
 * Why governance answered as it did. Carried in the [PolicyCheckOutcome]'s
 * metadata (see [AccessDecisionMetadata]) rather than as a second outcome
 * vocabulary: the outcome itself is always the existing
 * [PolicyCheckOutcome.Allow] or [PolicyCheckOutcome.Deny].
 */
public enum class AccessDecisionReason {
    /** Principal and resource belong to different tenants. Hard denial. */
    TENANT_MISMATCH,

    /** The principal has no role binding in its tenant. Deny by default. */
    NO_ROLE_BINDING,

    /** A bound role explicitly denies the required permission. Beats any allow. */
    EXPLICIT_DENY,

    /** The principal has roles, but none allows the required permission. Deny by default. */
    NO_MATCHING_PERMISSION,

    /** At least one bound role allows the permission and none denies it. */
    ALLOWED_BY_ROLE,
}

/** Metadata keys and readers for outcomes produced by governance. */
public object AccessDecisionMetadata {
    /** Metadata key holding the [AccessDecisionReason] name. */
    public const val REASON_KEY: String = "governance.reason"

    /**
     * Metadata key holding the comma-separated, sorted ids of the roles that
     * decided the outcome (the denying roles for [AccessDecisionReason.EXPLICIT_DENY],
     * the allowing roles for [AccessDecisionReason.ALLOWED_BY_ROLE]). Absent
     * for the other reasons.
     */
    public const val DECIDING_ROLES_KEY: String = "governance.roles"

    internal fun of(reason: AccessDecisionReason, decidingRoles: List<RoleId> = emptyList()): DataLoomMetadata {
        val entries = LinkedHashMap<String, String>()
        entries[REASON_KEY] = reason.name
        if (decidingRoles.isNotEmpty()) {
            entries[DECIDING_ROLES_KEY] = decidingRoles.map { it.value }.sorted().joinToString(",")
        }
        return DataLoomMetadata.of(entries)
    }
}

/**
 * The [AccessDecisionReason] recorded on this outcome by governance, or `null`
 * if the outcome was not produced by governance.
 */
public val PolicyCheckOutcome.accessDecisionReason: AccessDecisionReason?
    get() {
        val name = metadata[AccessDecisionMetadata.REASON_KEY] ?: return null
        return AccessDecisionReason.entries.firstOrNull { it.name == name }
    }
