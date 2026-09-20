package io.dataloom.governance.rbac

import io.dataloom.api.identifier.TenantId
import io.dataloom.api.policy.PolicyCheckOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RbacEvaluatorTest {

    private val t1 = TenantId("tenant-one")
    private val t2 = TenantId("tenant-two")

    private val queue = ResourceType("queue")
    private val retry = ResourceType("retry.command")
    private val config = ResourceType("configuration")
    private val support = ResourceType("support.bundle")
    private val audit = ResourceType("audit.log")

    private val viewer = Role(RoleId("viewer"), allow = setOf(Permission(Action.READ, queue), Permission(Action.READ, retry)))
    private val operator = Role(
        RoleId("operator"),
        allow = setOf(Permission(Action.EXECUTE, retry), Permission(Action.EXECUTE, queue), Permission(Action.READ, queue)),
    )
    private val admin = Role(
        RoleId("admin"),
        allow = setOf(Permission(Action.ADMINISTER, support), Permission(Action.WRITE, config)),
    )
    private val restricted = Role(RoleId("restricted"), allow = setOf(Permission(Action.READ, audit)), deny = setOf(Permission(Action.EXECUTE, retry)))
    private val denyOnly = Role(RoleId("no-queue-read"), allow = emptySet(), deny = setOf(Permission(Action.READ, queue)))

    private fun principal(id: String, tenant: TenantId) = Principal(PrincipalId(id), tenant)

    private val alice1 = principal("alice", t1)
    private val bob1 = principal("bob", t1)
    private val carol1 = principal("carol", t1)
    private val dave1 = principal("dave", t1)
    private val erin1 = principal("erin", t1)
    private val alice2 = principal("alice", t2)

    private val policy = RbacPolicy(
        roles = listOf(viewer, operator, admin, restricted, denyOnly),
        bindings = listOf(
            RoleBinding(alice1.id, t1, viewer.id),
            RoleBinding(bob1.id, t1, viewer.id),
            RoleBinding(bob1.id, t1, operator.id),
            RoleBinding(carol1.id, t1, operator.id),
            RoleBinding(carol1.id, t1, restricted.id),
            RoleBinding(erin1.id, t1, operator.id),
            RoleBinding(erin1.id, t1, denyOnly.id),
            // Same principal id, different tenant, different role: must never bleed into tenant one.
            RoleBinding(alice2.id, t2, admin.id),
        ),
    )
    private val evaluator = RbacEvaluator(policy)

    private data class Case(
        val name: String,
        val principal: Principal,
        val action: Action,
        val resourceTenant: TenantId,
        val resourceType: ResourceType,
        val allowed: Boolean,
        val reason: AccessDecisionReason,
        val decidingRoles: String?,
    )

    private val cases: List<Case> = listOf(
        // Deny by default.
        Case("principal with no binding is denied", dave1, Action.READ, t1, queue, false, AccessDecisionReason.NO_ROLE_BINDING, null),
        Case("role lacking the action is denied", alice1, Action.WRITE, t1, queue, false, AccessDecisionReason.NO_MATCHING_PERMISSION, null),
        Case("role lacking the resource type is denied", alice1, Action.READ, t1, config, false, AccessDecisionReason.NO_MATCHING_PERMISSION, null),
        Case("ADMINISTER does not imply READ", alice2, Action.READ, t2, support, false, AccessDecisionReason.NO_MATCHING_PERMISSION, null),
        // Plain allow.
        Case("single role allow", alice1, Action.READ, t1, queue, true, AccessDecisionReason.ALLOWED_BY_ROLE, "viewer"),
        Case("admin allow in its own tenant", alice2, Action.ADMINISTER, t2, support, true, AccessDecisionReason.ALLOWED_BY_ROLE, "admin"),
        // Multi-role union.
        Case("union: permission only in second role", bob1, Action.EXECUTE, t1, retry, true, AccessDecisionReason.ALLOWED_BY_ROLE, "operator"),
        Case("union: permission only in first role", bob1, Action.READ, t1, retry, true, AccessDecisionReason.ALLOWED_BY_ROLE, "viewer"),
        Case("union: permission granted by both roles lists both", bob1, Action.READ, t1, queue, true, AccessDecisionReason.ALLOWED_BY_ROLE, "operator,viewer"),
        Case("union does not invent permissions", bob1, Action.WRITE, t1, config, false, AccessDecisionReason.NO_MATCHING_PERMISSION, null),
        // Explicit deny beats allow.
        Case("deny in one role beats allow in another", carol1, Action.EXECUTE, t1, retry, false, AccessDecisionReason.EXPLICIT_DENY, "restricted"),
        Case("deny-only role beats allow in another role", erin1, Action.READ, t1, queue, false, AccessDecisionReason.EXPLICIT_DENY, "no-queue-read"),
        Case("deny does not affect other permissions", carol1, Action.EXECUTE, t1, queue, true, AccessDecisionReason.ALLOWED_BY_ROLE, "operator"),
        Case("allow in the denying role itself still works elsewhere", carol1, Action.READ, t1, audit, true, AccessDecisionReason.ALLOWED_BY_ROLE, "restricted"),
        // Tenant mismatch: hard denial even where the role would allow.
        Case("cross-tenant read is denied", alice1, Action.READ, t2, queue, false, AccessDecisionReason.TENANT_MISMATCH, null),
        Case("cross-tenant admin is denied", alice2, Action.ADMINISTER, t1, support, false, AccessDecisionReason.TENANT_MISMATCH, null),
        Case("cross-tenant beats a permission that exists in the resource tenant", bob1, Action.EXECUTE, t2, retry, false, AccessDecisionReason.TENANT_MISMATCH, null),
        Case("same principal id in another tenant gets no role from this tenant", alice1, Action.ADMINISTER, t1, support, false, AccessDecisionReason.NO_MATCHING_PERMISSION, null),
        Case("unbound principal, cross-tenant, is a tenant denial not a binding denial", dave1, Action.READ, t2, queue, false, AccessDecisionReason.TENANT_MISMATCH, null),
    )

    @Test
    fun tableDrivenCases() {
        for (case in cases) {
            val outcome = evaluator.evaluate(AccessRequest(case.principal, case.action, ResourceRef(case.resourceTenant, case.resourceType)))
            val label = case.name
            if (case.allowed) {
                assertIs<PolicyCheckOutcome.Allow>(outcome, label)
            } else {
                assertIs<PolicyCheckOutcome.Deny>(outcome, label)
            }
            assertEquals(case.reason, outcome.accessDecisionReason, label)
            assertEquals(case.decidingRoles, outcome.metadata[AccessDecisionMetadata.DECIDING_ROLES_KEY], label)
        }
    }

    @Test
    fun evaluationIsDeterministic() {
        val request = AccessRequest(bob1, Action.READ, ResourceRef(t1, queue))
        assertEquals(evaluator.evaluate(request), evaluator.evaluate(request))
        assertEquals(evaluator.evaluate(request), RbacEvaluator(policy).evaluate(request))
    }

    @Test
    fun bindingOrderDoesNotChangeTheOutcome() {
        val reversed = RbacEvaluator(
            RbacPolicy(roles = policy.roles.reversed(), bindings = policy.bindings.reversed()),
        )
        for (case in cases) {
            val request = AccessRequest(case.principal, case.action, ResourceRef(case.resourceTenant, case.resourceType))
            assertEquals(evaluator.evaluate(request), reversed.evaluate(request), case.name)
        }
    }

    /**
     * Independent oracle: recompute the expected answer from first principles
     * for the full cross-product of principals, actions, resource types and
     * resource tenants, and require the evaluator to agree on every cell.
     */
    @Test
    fun exhaustiveCrossProductAgreesWithIndependentOracle() {
        val principals = listOf(alice1, bob1, carol1, dave1, erin1, alice2, principal("nobody", t2))
        val tenants = listOf(t1, t2)
        val types = listOf(queue, retry, config, support, audit, ResourceType("unlisted"))
        var evaluated = 0
        for (p in principals) {
            for (tenant in tenants) {
                for (type in types) {
                    for (action in Action.entries) {
                        val roles = policy.bindings.filter { it.principalId == p.id && it.tenantId == p.tenantId }
                            .map { binding -> policy.roles.first { it.id == binding.roleId } }
                        val needed = Permission(action, type)
                        val expectedAllowed = p.tenantId == tenant &&
                            roles.none { needed in it.deny } &&
                            roles.any { needed in it.allow }

                        val outcome = evaluator.evaluate(AccessRequest(p, action, ResourceRef(tenant, type)))
                        assertEquals(
                            expectedAllowed,
                            outcome is PolicyCheckOutcome.Allow,
                            "principal=${p.id}@${p.tenantId} action=$action resource=$type@$tenant",
                        )
                        evaluated += 1
                    }
                }
            }
        }
        assertEquals(7 * 2 * 6 * Action.entries.size, evaluated)
    }

    @Test
    fun emptyPolicyDeniesEverything() {
        val empty = RbacEvaluator(RbacPolicy(emptyList(), emptyList()))
        for (action in Action.entries) {
            val outcome = empty.evaluate(AccessRequest(alice1, action, ResourceRef(t1, queue)))
            assertIs<PolicyCheckOutcome.Deny>(outcome)
            assertEquals(AccessDecisionReason.NO_ROLE_BINDING, outcome.accessDecisionReason)
        }
    }

    @Test
    fun outcomesUseOnlyAllowAndDeny() {
        for (case in cases) {
            val outcome = evaluator.evaluate(AccessRequest(case.principal, case.action, ResourceRef(case.resourceTenant, case.resourceType)))
            assertTrue(outcome is PolicyCheckOutcome.Allow || outcome is PolicyCheckOutcome.Deny, case.name)
        }
    }

    @Test
    fun outcomesNeverLeakTenantOrPrincipalIds() {
        val identifying = listOf(t1.value, t2.value, "alice", "bob", "carol", "dave", "erin")
        for (case in cases) {
            val outcome = evaluator.evaluate(AccessRequest(case.principal, case.action, ResourceRef(case.resourceTenant, case.resourceType)))
            val rendered = outcome.justification + outcome.metadata.entries.entries.joinToString { "${it.key}=${it.value}" }
            for (secret in identifying) {
                assertFalse(rendered.contains(secret), "${case.name} leaked '$secret' in: $rendered")
            }
        }
    }

    @Test
    fun accessDecisionReasonIsNullForOutcomesNotProducedByGovernance() {
        assertNull(PolicyCheckOutcome.Allow("elsewhere").accessDecisionReason)
    }

    @Test
    fun requiredPermissionIsTheExactActionAndResourceType() {
        val request = AccessRequest(alice1, Action.EXECUTE, ResourceRef(t1, retry))
        assertEquals(Permission(Action.EXECUTE, retry), request.requiredPermission)
    }
}
