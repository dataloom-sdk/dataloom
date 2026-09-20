package io.dataloom.governance.rbac

import io.dataloom.api.identifier.TenantId
import io.dataloom.api.policy.PolicyCheckOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TenantGuardTest {

    private val queue = ResourceType("queue")
    private val principal = Principal(PrincipalId("alice"), TenantId("acme"))

    @Test
    fun sameTenantIsAllowed() {
        val outcome = TenantGuard.check(principal, ResourceRef(TenantId("acme"), queue))
        assertIs<PolicyCheckOutcome.Allow>(outcome)
    }

    @Test
    fun differentTenantIsAHardDenial() {
        val outcome = TenantGuard.check(principal, ResourceRef(TenantId("globex"), queue))
        assertIs<PolicyCheckOutcome.Deny>(outcome)
        assertEquals(AccessDecisionReason.TENANT_MISMATCH, outcome.accessDecisionReason)
    }

    @Test
    fun tenantComparisonIsExactAndCaseSensitive() {
        for (near in listOf("ACME", "acme ", " acme", "acme2", "acm")) {
            val outcome = TenantGuard.check(principal, ResourceRef(TenantId(near), queue))
            assertIs<PolicyCheckOutcome.Deny>(outcome, "tenant '$near' must not match 'acme'")
        }
    }

    @Test
    fun wildcardTenantIsJustAnotherNonMatchingTenant() {
        val outcome = TenantGuard.check(principal, ResourceRef(TenantId("*"), queue))
        assertIs<PolicyCheckOutcome.Deny>(outcome)
        val wildcardPrincipal = Principal(PrincipalId("root"), TenantId("*"))
        assertIs<PolicyCheckOutcome.Deny>(TenantGuard.check(wildcardPrincipal, ResourceRef(TenantId("acme"), queue)))
    }

    @Test
    fun denialDoesNotRevealEitherTenant() {
        val outcome = TenantGuard.check(principal, ResourceRef(TenantId("globex"), queue))
        val rendered = outcome.justification + outcome.metadata.entries
        assertFalse(rendered.contains("acme"))
        assertFalse(rendered.contains("globex"))
    }

    @Test
    fun evaluatorAppliesTheGuardBeforeConsultingAnyRole() {
        // A role that would allow the action must not matter across tenants.
        val role = Role(RoleId("everything"), allow = Action.entries.map { Permission(it, queue) }.toSet())
        val policy = RbacPolicy(listOf(role), listOf(RoleBinding(principal.id, principal.tenantId, role.id)))
        val evaluator = RbacEvaluator(policy)
        for (action in Action.entries) {
            val cross = evaluator.evaluate(AccessRequest(principal, action, ResourceRef(TenantId("globex"), queue)))
            assertIs<PolicyCheckOutcome.Deny>(cross, "$action")
            assertEquals(AccessDecisionReason.TENANT_MISMATCH, cross.accessDecisionReason)
            val same = evaluator.evaluate(AccessRequest(principal, action, ResourceRef(TenantId("acme"), queue)))
            assertIs<PolicyCheckOutcome.Allow>(same, "$action")
        }
    }
}
