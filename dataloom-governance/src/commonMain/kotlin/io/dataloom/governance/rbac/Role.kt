package io.dataloom.governance.rbac

import io.dataloom.api.identifier.TenantId
import io.dataloom.governance.requireVocabularyIdentifier
import kotlin.jvm.JvmInline

/**
 * Identifier of a [Role]. Same closed charset as [ResourceType].
 */
@JvmInline
public value class RoleId(
    /** Underlying role identifier value. */
    public val value: String,
) {
    init {
        requireVocabularyIdentifier("RoleId", value)
    }

    override fun toString(): String = value
}

/**
 * A named, immutable set of [Permission]s.
 *
 * A role lists what it [allow]s and, separately, what it [deny]s. An explicit
 * deny in any role bound to a principal overrides an allow in any other role
 * bound to the same principal (see [RbacEvaluator]). A role that both allows
 * and denies the same permission is contradictory and is rejected at
 * construction rather than resolved silently.
 *
 * Both sets are defensively copied and bounded by [MAXIMUM_PERMISSIONS_PER_SET].
 *
 * @throws IllegalArgumentException if the role has no permissions at all, a set
 *   exceeds the bound, or [allow] and [deny] overlap.
 */
public class Role(
    public val id: RoleId,
    allow: Set<Permission>,
    deny: Set<Permission> = emptySet(),
) {
    /** Permissions this role grants. */
    public val allow: Set<Permission> = allow.toSet()

    /** Permissions this role explicitly prohibits. Deny always beats allow. */
    public val deny: Set<Permission> = deny.toSet()

    init {
        require(this.allow.isNotEmpty() || this.deny.isNotEmpty()) {
            "Role ${id.value} must declare at least one allowed or denied permission."
        }
        require(this.allow.size <= MAXIMUM_PERMISSIONS_PER_SET) {
            "Role ${id.value} must not allow more than $MAXIMUM_PERMISSIONS_PER_SET permissions."
        }
        require(this.deny.size <= MAXIMUM_PERMISSIONS_PER_SET) {
            "Role ${id.value} must not deny more than $MAXIMUM_PERMISSIONS_PER_SET permissions."
        }
        require(this.allow.none { it in this.deny }) {
            "Role ${id.value} must not both allow and deny the same permission."
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Role) return false
        return id == other.id && allow == other.allow && deny == other.deny
    }

    override fun hashCode(): Int {
        var result: Int = id.hashCode()
        result = 31 * result + allow.hashCode()
        result = 31 * result + deny.hashCode()
        return result
    }

    override fun toString(): String = "Role(id=$id, allowCount=${allow.size}, denyCount=${deny.size})"

    public companion object {
        /** Upper bound on the size of each of a role's allow and deny sets. */
        public const val MAXIMUM_PERMISSIONS_PER_SET: Int = 256
    }
}

/**
 * Binds one [PrincipalId] to one [Role] within exactly one [TenantId].
 *
 * The tenant is part of the binding identity and is mandatory: a binding only
 * ever applies to the principal of that same tenant. There is no wildcard
 * tenant and no cross-tenant binding in V1.
 */
public data class RoleBinding(
    public val principalId: PrincipalId,
    public val tenantId: TenantId,
    public val roleId: RoleId,
)
