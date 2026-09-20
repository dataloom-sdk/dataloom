package io.dataloom.runtime.conflict

import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule.ForEntityType
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule.ForTenant
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule.ForWorkflow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for [ConflictResolverSelectionPolicy] precedence and
 * [ConflictOrchestrationBindings.selectResolverId]. No registry, detector, or
 * orchestrator is involved: selection is a pure function of the policy, the
 * global default, and the context.
 */
class ConflictResolverSelectionPolicyTest {

    private val invoice = EntityType("invoice")
    private val document = EntityType("document")
    private val workflowA = WorkflowId("workflow-a")
    private val workflowB = WorkflowId("workflow-b")
    private val tenantX = TenantId("tenant-x")
    private val tenantY = TenantId("tenant-y")

    private val entityResolver = ConflictResolverId("resolver.entity")
    private val workflowResolver = ConflictResolverId("resolver.workflow")
    private val tenantResolver = ConflictResolverId("resolver.tenant")
    private val globalResolver = ConflictResolverId("resolver.global")

    private val detectorId = ConflictDetectorId("detector")

    private fun context(
        entityType: EntityType = invoice,
        workflowId: WorkflowId = workflowA,
        tenantId: TenantId? = tenantX,
    ) = ConflictResolverSelectionContext(entityType, workflowId, tenantId)

    private fun bindings(
        policy: ConflictResolverSelectionPolicy?,
        global: ConflictResolverId? = globalResolver,
    ) = ConflictOrchestrationBindings(detectorId, global, policy)

    private val entityRule = ForEntityType(invoice, entityResolver)
    private val workflowRule = ForWorkflow(workflowA, workflowResolver)
    private val tenantRule = ForTenant(tenantX, tenantResolver)

    // -------------------------------------------------------------------------
    // Each tier alone
    // -------------------------------------------------------------------------

    @Test
    fun entityTypeRuleAlone_selectsItsResolver() {
        assertEquals(entityResolver, ConflictResolverSelectionPolicy(listOf(entityRule)).select(context()))
    }

    @Test
    fun workflowRuleAlone_selectsItsResolver() {
        assertEquals(workflowResolver, ConflictResolverSelectionPolicy(listOf(workflowRule)).select(context()))
    }

    @Test
    fun tenantRuleAlone_selectsItsResolver() {
        assertEquals(tenantResolver, ConflictResolverSelectionPolicy(listOf(tenantRule)).select(context()))
    }

    @Test
    fun emptyPolicy_selectsNothing() {
        assertNull(ConflictResolverSelectionPolicy(emptyList()).select(context()))
    }

    // -------------------------------------------------------------------------
    // Rules whose key does not match the context never apply
    // -------------------------------------------------------------------------

    @Test
    fun rulesForOtherKeys_doNotMatch() {
        val policy = ConflictResolverSelectionPolicy(
            listOf(
                ForEntityType(document, entityResolver),
                ForWorkflow(workflowB, workflowResolver),
                ForTenant(tenantY, tenantResolver),
            ),
        )
        assertNull(policy.select(context()))
    }

    // -------------------------------------------------------------------------
    // Precedence: every pairwise combination and the full stack
    // -------------------------------------------------------------------------

    private class PrecedenceCase(
        val name: String,
        val rules: List<ConflictResolverSelectionRule>,
        val expected: ConflictResolverId,
    )

    private val precedenceCases = listOf(
        PrecedenceCase("entity beats workflow", listOf(entityRule, workflowRule), entityResolver),
        PrecedenceCase("entity beats tenant", listOf(entityRule, tenantRule), entityResolver),
        PrecedenceCase("workflow beats tenant", listOf(workflowRule, tenantRule), workflowResolver),
        PrecedenceCase("entity beats workflow and tenant", listOf(entityRule, workflowRule, tenantRule), entityResolver),
    )

    @Test
    fun precedence_mostSpecificTierWins_regardlessOfRuleOrder() {
        for (case in precedenceCases) {
            val forward = ConflictResolverSelectionPolicy(case.rules).select(context())
            val reversed = ConflictResolverSelectionPolicy(case.rules.reversed()).select(context())
            assertEquals(case.expected, forward, case.name)
            assertEquals(case.expected, reversed, "${case.name} (reversed rule order)")
        }
    }

    @Test
    fun precedence_lowerTierApplies_whenHigherTierDoesNotMatch() {
        val workflowAndTenant = ConflictResolverSelectionPolicy(listOf(workflowRule, tenantRule))
        // Entity type has no rule; workflow rule matches; tenant rule also matches but loses.
        assertEquals(workflowResolver, workflowAndTenant.select(context(entityType = document)))
        // Workflow does not match either: tenant rule is what is left.
        assertEquals(tenantResolver, workflowAndTenant.select(context(entityType = document, workflowId = workflowB)))
        // Nothing matches.
        assertNull(workflowAndTenant.select(context(entityType = document, workflowId = workflowB, tenantId = tenantY)))
    }

    @Test
    fun precedence_higherTierWinsOnlyForItsOwnKey() {
        val policy = ConflictResolverSelectionPolicy(listOf(entityRule, workflowRule))
        assertEquals(entityResolver, policy.select(context(entityType = invoice)))
        assertEquals(workflowResolver, policy.select(context(entityType = document)))
    }

    @Test
    fun sameResolverIdInSeveralTiers_isNotATie() {
        val policy = ConflictResolverSelectionPolicy(
            listOf(ForEntityType(invoice, entityResolver), ForWorkflow(workflowA, entityResolver)),
        )
        assertEquals(entityResolver, policy.select(context()))
    }

    // -------------------------------------------------------------------------
    // Tenant absent
    // -------------------------------------------------------------------------

    @Test
    fun tenantAbsent_tenantRuleNeverMatches() {
        val policy = ConflictResolverSelectionPolicy(listOf(tenantRule))
        assertNull(policy.select(context(tenantId = null)))
    }

    @Test
    fun tenantAbsent_higherTiersStillApply_andGlobalFallsThrough() {
        val policy = ConflictResolverSelectionPolicy(listOf(entityRule, tenantRule))
        assertEquals(entityResolver, policy.select(context(tenantId = null)))
        assertEquals(
            globalResolver,
            bindings(ConflictResolverSelectionPolicy(listOf(tenantRule)))
                .selectResolverId(context(tenantId = null)),
        )
    }

    @Test
    fun tenantPresentButUnruled_fallsThroughToGlobal() {
        assertEquals(
            globalResolver,
            bindings(ConflictResolverSelectionPolicy(listOf(tenantRule)))
                .selectResolverId(context(tenantId = tenantY)),
        )
    }

    // -------------------------------------------------------------------------
    // Global default (ConflictOrchestrationBindings.resolverId)
    // -------------------------------------------------------------------------

    @Test
    fun globalDefault_usedWhenPolicyMatchesNothing() {
        val policy = ConflictResolverSelectionPolicy(listOf(ForEntityType(document, entityResolver)))
        assertEquals(globalResolver, bindings(policy).selectResolverId(context()))
    }

    @Test
    fun globalDefault_losesToEveryTier() {
        for (rule in listOf(entityRule, workflowRule, tenantRule)) {
            assertEquals(
                rule.resolverId,
                bindings(ConflictResolverSelectionPolicy(listOf(rule))).selectResolverId(context()),
            )
        }
    }

    @Test
    fun noGlobalDefault_andNoMatch_selectsNothing() {
        val policy = ConflictResolverSelectionPolicy(listOf(ForEntityType(document, entityResolver)))
        assertNull(bindings(policy, global = null).selectResolverId(context()))
    }

    @Test
    fun noGlobalDefault_tiersStillSelect() {
        assertEquals(
            workflowResolver,
            bindings(ConflictResolverSelectionPolicy(listOf(workflowRule)), global = null)
                .selectResolverId(context()),
        )
    }

    // -------------------------------------------------------------------------
    // No policy == legacy exact-ID behaviour
    // -------------------------------------------------------------------------

    @Test
    fun noPolicy_isTheLegacyExactIdBinding() {
        val contexts = listOf(
            context(),
            context(entityType = document),
            context(workflowId = workflowB),
            context(tenantId = null),
            context(tenantId = tenantY),
        )
        for (ctx in contexts) {
            assertEquals(globalResolver, ConflictOrchestrationBindings(detectorId, globalResolver).selectResolverId(ctx))
            assertNull(ConflictOrchestrationBindings(detectorId, null).selectResolverId(ctx))
            assertEquals(globalResolver, bindings(policy = null).selectResolverId(ctx))
        }
    }

    @Test
    fun emptyPolicy_behavesLikeNoPolicy() {
        val empty = bindings(ConflictResolverSelectionPolicy(emptyList()))
        assertEquals(globalResolver, empty.selectResolverId(context()))
        assertNull(bindings(ConflictResolverSelectionPolicy(emptyList()), global = null).selectResolverId(context()))
    }

    @Test
    fun twoArgumentBindings_haveNoPolicy() {
        assertNull(ConflictOrchestrationBindings(detectorId, globalResolver).resolverSelectionPolicy)
    }

    // -------------------------------------------------------------------------
    // Ties are rejected at construction
    // -------------------------------------------------------------------------

    @Test
    fun duplicateEntityTypeRule_isRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            ConflictResolverSelectionPolicy(
                listOf(ForEntityType(invoice, entityResolver), ForEntityType(invoice, workflowResolver)),
            )
        }
        assertTrue("entity-type" in e.message.orEmpty() && "invoice" in e.message.orEmpty(), e.message)
    }

    @Test
    fun duplicateWorkflowRule_isRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            ConflictResolverSelectionPolicy(
                listOf(ForWorkflow(workflowA, entityResolver), ForWorkflow(workflowA, workflowResolver)),
            )
        }
        assertTrue("workflow" in e.message.orEmpty() && "workflow-a" in e.message.orEmpty(), e.message)
    }

    @Test
    fun duplicateTenantRule_isRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            ConflictResolverSelectionPolicy(
                listOf(ForTenant(tenantX, entityResolver), ForTenant(tenantX, tenantResolver)),
            )
        }
        assertTrue("tenant" in e.message.orEmpty() && "tenant-x" in e.message.orEmpty(), e.message)
    }

    @Test
    fun duplicateRuleWithSameResolver_isStillRejected() {
        assertFailsWith<IllegalArgumentException> {
            ConflictResolverSelectionPolicy(listOf(entityRule, entityRule))
        }
    }

    @Test
    fun sameKeyValueInDifferentTiers_isNotATie() {
        // "shared" as an entity type, a workflow, and a tenant are three different keys.
        ConflictResolverSelectionPolicy(
            listOf(
                ForEntityType(EntityType("shared"), entityResolver),
                ForWorkflow(WorkflowId("shared"), workflowResolver),
                ForTenant(TenantId("shared"), tenantResolver),
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Value semantics
    // -------------------------------------------------------------------------

    @Test
    fun rules_areDefensivelyCopied() {
        val supplied = mutableListOf<ConflictResolverSelectionRule>(entityRule)
        val policy = ConflictResolverSelectionPolicy(supplied)
        supplied += workflowRule
        assertEquals(listOf<ConflictResolverSelectionRule>(entityRule), policy.rules)
        assertEquals(entityResolver, policy.select(context()))
    }

    @Test
    fun equality_isOrderInsensitiveAndValueBased() {
        val a = ConflictResolverSelectionPolicy(listOf(entityRule, workflowRule))
        val b = ConflictResolverSelectionPolicy(listOf(workflowRule, entityRule))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, ConflictResolverSelectionPolicy(listOf(entityRule)))
        assertEquals(bindings(a), bindings(b))
        assertNotEquals(bindings(a), bindings(null))
    }

    @Test
    fun toString_listsTierKeyAndResolver_only() {
        val text = ConflictResolverSelectionPolicy(listOf(entityRule, workflowRule, tenantRule)).toString()
        assertEquals(
            "ConflictResolverSelectionPolicy(rules=[" +
                "entityType=invoice->resolver.entity, " +
                "workflow=workflow-a->resolver.workflow, " +
                "tenant=tenant-x->resolver.tenant])",
            text,
        )
    }
}
