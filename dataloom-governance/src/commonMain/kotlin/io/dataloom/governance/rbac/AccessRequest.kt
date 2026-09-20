package io.dataloom.governance.rbac

import io.dataloom.api.identifier.TenantId

/**
 * A governed resource: its [type] and the single [tenantId] that owns it.
 *
 * The tenant is mandatory and singular. Resources are never tenant-less or
 * shared across tenants in V1.
 */
public data class ResourceRef(
    public val tenantId: TenantId,
    public val type: ResourceType,
)

/**
 * A question for [RbacEvaluator]: may [principal] perform [action] on
 * [resource]?
 *
 * A request whose principal and resource belong to different tenants is a
 * well-formed request that is answered with a deny; it is not a construction
 * error, so callers cannot distinguish "invalid" from "denied" by exception.
 */
public data class AccessRequest(
    public val principal: Principal,
    public val action: Action,
    public val resource: ResourceRef,
) {
    /** The exact [Permission] this request needs. */
    public val requiredPermission: Permission
        get() = Permission(action, resource.type)
}
