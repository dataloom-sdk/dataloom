package io.dataloom.runtime.conflict

import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The tier reported for observability must always agree with the resolver ID
 * selection actually uses, for every combination of matching tiers.
 */
class ConflictResolverSelectionTierTest {

    private val entityType = EntityType("invoice")
    private val workflowId = WorkflowId("wf")
    private val tenantId = TenantId("t")
    private val context = ConflictResolverSelectionContext(entityType, workflowId, tenantId)
    private val entityResolver = ConflictResolverId("r.entity")
    private val workflowResolver = ConflictResolverId("r.workflow")
    private val tenantResolver = ConflictResolverId("r.tenant")
    private val globalResolver = ConflictResolverId("r.global")

    private fun rules(entity: Boolean, workflow: Boolean, tenant: Boolean) = buildList<ConflictResolverSelectionRule> {
        if (entity) add(ConflictResolverSelectionRule.ForEntityType(entityType, entityResolver))
        if (workflow) add(ConflictResolverSelectionRule.ForWorkflow(workflowId, workflowResolver))
        if (tenant) add(ConflictResolverSelectionRule.ForTenant(tenantId, tenantResolver))
    }

    @Test
    fun everyTierCombinationReportsTheTierThatSelectionUses() {
        for (entity in listOf(false, true)) for (workflow in listOf(false, true)) for (tenant in listOf(false, true)) {
            for (withGlobal in listOf(false, true)) {
                val bindings = ConflictOrchestrationBindings(
                    detectorId = ConflictDetectorId("d"),
                    resolverId = if (withGlobal) globalResolver else null,
                    resolverSelectionPolicy = ConflictResolverSelectionPolicy(rules(entity, workflow, tenant)),
                )
                val expectedTier = when {
                    entity -> ConflictResolverSelectionTier.ENTITY_TYPE
                    workflow -> ConflictResolverSelectionTier.WORKFLOW
                    tenant -> ConflictResolverSelectionTier.TENANT
                    withGlobal -> ConflictResolverSelectionTier.GLOBAL
                    else -> null
                }
                val expectedId = when (expectedTier) {
                    ConflictResolverSelectionTier.ENTITY_TYPE -> entityResolver
                    ConflictResolverSelectionTier.WORKFLOW -> workflowResolver
                    ConflictResolverSelectionTier.TENANT -> tenantResolver
                    ConflictResolverSelectionTier.GLOBAL -> globalResolver
                    null -> null
                }
                val label = "entity=$entity workflow=$workflow tenant=$tenant global=$withGlobal"
                assertEquals(expectedTier, bindings.selectedTier(context), label)
                assertEquals(expectedId, bindings.selectResolverId(context), label)
            }
        }
    }

    @Test
    fun aTenantRuleNeverMatchesAContextWithoutATenant() {
        val policy = ConflictResolverSelectionPolicy(rules(entity = false, workflow = false, tenant = true))
        assertNull(policy.matchedTier(context.copy(tenantId = null)))
        assertNull(policy.select(context.copy(tenantId = null)))
    }

    @Test
    fun thePolicyItselfNeverReportsTheGlobalTier() {
        val policy = ConflictResolverSelectionPolicy(emptyList())
        assertNull(policy.matchedTier(context))
    }
}
