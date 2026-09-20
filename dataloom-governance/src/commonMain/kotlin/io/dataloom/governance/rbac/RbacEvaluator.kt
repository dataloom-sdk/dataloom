package io.dataloom.governance.rbac

import io.dataloom.api.policy.PolicyCheckOutcome

/**
 * Deterministic, side-effect-free RBAC evaluator: the governance boundary.
 *
 * It answers with the existing policy-foundation vocabulary
 * ([PolicyCheckOutcome.Allow] or [PolicyCheckOutcome.Deny]); governance does not
 * define a second outcome type. [PolicyCheckOutcome.RequireUserAction] and
 * [PolicyCheckOutcome.Defer] are never produced by this evaluator. The reason
 * is available through [accessDecisionReason].
 *
 * ## Evaluation order
 *
 * 1. **Tenant isolation** ([TenantGuard]). A principal whose tenant differs
 *    from the resource's tenant is denied before any role is consulted.
 * 2. **Bindings.** Only roles bound to exactly `(principal id, principal
 *    tenant)` participate. No binding means deny.
 * 3. **Explicit deny.** If any bound role denies the required [Permission],
 *    the outcome is deny, regardless of how many roles allow it.
 * 4. **Allow.** Otherwise, if any bound role allows the permission, the
 *    outcome is allow (permissions from multiple roles are unioned).
 * 5. **Default deny.** Otherwise deny.
 *
 * Permissions match exactly on `(action, resource type)`. There is no
 * wildcard, no action hierarchy, and no inheritance between roles.
 *
 * ## Determinism and safety
 *
 * The same [RbacPolicy] and [AccessRequest] always produce an equal outcome.
 * Outcomes never contain tenant ids or principal ids; they carry only the
 * [AccessDecisionReason] and, where relevant, the sorted deciding role ids.
 */
public class RbacEvaluator(
    private val policy: RbacPolicy,
) {

    /** Evaluates [request] against this evaluator's [RbacPolicy]. */
    public fun evaluate(request: AccessRequest): PolicyCheckOutcome {
        val tenantOutcome = TenantGuard.check(request.principal, request.resource)
        if (tenantOutcome is PolicyCheckOutcome.Deny) {
            return tenantOutcome
        }

        val boundRoles = policy.rolesBoundTo(request.principal)
        if (boundRoles.isEmpty()) {
            return PolicyCheckOutcome.Deny(
                justification = "Principal has no role binding in its tenant.",
                metadata = AccessDecisionMetadata.of(AccessDecisionReason.NO_ROLE_BINDING),
            )
        }

        val required = request.requiredPermission

        val denyingRoles = boundRoles.filter { required in it.deny }
        if (denyingRoles.isNotEmpty()) {
            return PolicyCheckOutcome.Deny(
                justification = "Permission is explicitly denied by a bound role.",
                metadata = AccessDecisionMetadata.of(AccessDecisionReason.EXPLICIT_DENY, denyingRoles.map { it.id }),
            )
        }

        val allowingRoles = boundRoles.filter { required in it.allow }
        if (allowingRoles.isNotEmpty()) {
            return PolicyCheckOutcome.Allow(
                justification = "Permission is granted by a bound role.",
                metadata = AccessDecisionMetadata.of(AccessDecisionReason.ALLOWED_BY_ROLE, allowingRoles.map { it.id }),
            )
        }

        return PolicyCheckOutcome.Deny(
            justification = "No bound role grants the required permission.",
            metadata = AccessDecisionMetadata.of(AccessDecisionReason.NO_MATCHING_PERMISSION),
        )
    }
}
