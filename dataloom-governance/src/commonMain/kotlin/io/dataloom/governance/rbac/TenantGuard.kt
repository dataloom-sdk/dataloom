package io.dataloom.governance.rbac

import io.dataloom.api.policy.PolicyCheckOutcome

/**
 * Tenant-isolation guard: a principal may only reach a resource owned by its
 * own tenant.
 *
 * V1 has no cross-tenant grant type, so there is no exception path: a tenant
 * mismatch is always a hard denial. If a cross-tenant grant is ever added it
 * must be an explicit, audited type (see the governance ADR); it must not be
 * expressed as a special tenant value or a wildcard.
 *
 * The denial justification and metadata deliberately do not include either
 * tenant id, so a denial cannot be used to probe which tenant owns a resource.
 *
 * [RbacEvaluator] applies this guard before it consults any role, so role
 * bindings can never widen access across a tenant boundary.
 */
public object TenantGuard {

    /**
     * Returns [PolicyCheckOutcome.Allow] when [principal] and [resource] share
     * a tenant, otherwise [PolicyCheckOutcome.Deny] with
     * [AccessDecisionReason.TENANT_MISMATCH].
     */
    public fun check(principal: Principal, resource: ResourceRef): PolicyCheckOutcome =
        if (principal.tenantId == resource.tenantId) {
            PolicyCheckOutcome.Allow(
                justification = "Principal and resource belong to the same tenant.",
            )
        } else {
            PolicyCheckOutcome.Deny(
                justification = "Principal tenant does not match resource tenant.",
                metadata = AccessDecisionMetadata.of(AccessDecisionReason.TENANT_MISMATCH),
            )
        }
}
