package io.dataloom.runtime.conflict

import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId

/**
 * The facts a [ConflictResolverSelectionPolicy] may use to choose a
 * [ConflictResolverId] for one detected conflict.
 *
 * [SynchronizationConflictOrchestrator] builds this from values that are
 * already present on every detected conflict:
 *
 * - [entityType]: `SynchronizationConflict.entity.type` -- always present.
 * - [workflowId]: `SynchronizationRequest.workflowId` -- a required field, always
 *   present.
 * - [tenantId]: `SynchronizationRequest.context.tenantId` -- optional. `null`
 *   for any host that does not populate it; a tenant rule can never match a
 *   context whose [tenantId] is `null`.
 *
 * @param entityType the entity type of the conflicting entity.
 * @param workflowId the workflow that produced the synchronization request.
 * @param tenantId the tenant the host supplied on the request's
 *   `ExecutionContext`, or `null` when the host supplied none.
 */
public data class ConflictResolverSelectionContext(
    public val entityType: EntityType,
    public val workflowId: WorkflowId,
    public val tenantId: TenantId? = null,
)

/**
 * One tier-scoped rule of a [ConflictResolverSelectionPolicy]: "for this
 * entity type / workflow / tenant, use this resolver".
 *
 * The rule's subclass fixes its precedence tier; see
 * [ConflictResolverSelectionPolicy] for the tier order. A rule only *names* a
 * [ConflictResolverId]; it does not check that a resolver with that ID
 * exists. That happens at selection time through
 * [ConflictResolverRegistry.lookup], exactly as it does for
 * [ConflictOrchestrationBindings.resolverId].
 */
public sealed interface ConflictResolverSelectionRule {

    /** The resolver selected when this rule is the winning match. */
    public val resolverId: ConflictResolverId

    /** Highest-precedence tier: matches when the conflict's entity type equals [entityType]. */
    public data class ForEntityType(
        public val entityType: EntityType,
        override val resolverId: ConflictResolverId,
    ) : ConflictResolverSelectionRule

    /** Second tier: matches when the request's workflow equals [workflowId]. */
    public data class ForWorkflow(
        public val workflowId: WorkflowId,
        override val resolverId: ConflictResolverId,
    ) : ConflictResolverSelectionRule

    /**
     * Third tier: matches when the request carries a tenant equal to
     * [tenantId]. Never matches a request that carries no tenant.
     */
    public data class ForTenant(
        public val tenantId: TenantId,
        override val resolverId: ConflictResolverId,
    ) : ConflictResolverSelectionRule
}

/**
 * Immutable, deterministic mapping from a [ConflictResolverSelectionContext]
 * to a [ConflictResolverId], with strict precedence between tiers.
 *
 * ## Precedence
 *
 * The most specific tier with a matching rule wins:
 *
 * 1. **Entity type** -- [ConflictResolverSelectionRule.ForEntityType]
 * 2. **Workflow** -- [ConflictResolverSelectionRule.ForWorkflow]
 * 3. **Tenant** -- [ConflictResolverSelectionRule.ForTenant]
 * 4. **Global default** -- not stored here: it is
 *    [ConflictOrchestrationBindings.resolverId], the single resolver ID an
 *    application has always been able to bind, consulted only when this
 *    policy selects nothing. See [ConflictOrchestrationBindings.selectResolverId].
 *
 * A lower tier is never consulted once a higher tier matched, and the result
 * does not depend on rule order. [select] returns `null` when no rule
 * matches, so the caller falls through to the global default.
 *
 * ## Ties are rejected at construction
 *
 * Within one tier, each key (entity type, workflow, or tenant) may appear in
 * at most one rule. A second rule for the same key is rejected with
 * [IllegalArgumentException] -- even when both rules name the same
 * resolver -- so a policy that constructed successfully has exactly one
 * possible answer for every context. There is no "first rule wins" or "last
 * rule wins" behavior to depend on.
 *
 * ## Relationship to the registry
 *
 * The policy only chooses a [ConflictResolverId]. The chosen ID is then
 * resolved by [ConflictResolverRegistry.lookup] unchanged: an application
 * registration under that ID takes precedence over a built-in resolver with
 * the same ID, and an ID with neither yields the existing, well-defined
 * [ConflictOrchestrationResult.ResolverNotFound] outcome rather than an
 * exception.
 *
 * ## Value semantics
 *
 * The supplied collection is defensively copied. Equality is by rule set
 * (order-insensitive), which is safe because a valid policy cannot contain
 * two rules for the same tier and key.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only.
 *
 * @param rules the rules of every tier. May be empty, in which case [select]
 *   always returns `null` and the policy has no effect.
 * @throws IllegalArgumentException if two rules in the same tier share a key.
 */
public class ConflictResolverSelectionPolicy(
    rules: Collection<ConflictResolverSelectionRule>,
) {

    /** The rules in the order supplied. An unmodifiable snapshot. */
    public val rules: List<ConflictResolverSelectionRule> = rules.toList()

    private val entityTypeRules: Map<EntityType, ConflictResolverId>
    private val workflowRules: Map<WorkflowId, ConflictResolverId>
    private val tenantRules: Map<TenantId, ConflictResolverId>

    init {
        val byEntityType = LinkedHashMap<EntityType, ConflictResolverId>()
        val byWorkflow = LinkedHashMap<WorkflowId, ConflictResolverId>()
        val byTenant = LinkedHashMap<TenantId, ConflictResolverId>()
        for (rule in this.rules) {
            when (rule) {
                is ConflictResolverSelectionRule.ForEntityType ->
                    requireNoTie(byEntityType.put(rule.entityType, rule.resolverId), "entity-type", rule.entityType.value)
                is ConflictResolverSelectionRule.ForWorkflow ->
                    requireNoTie(byWorkflow.put(rule.workflowId, rule.resolverId), "workflow", rule.workflowId.value)
                is ConflictResolverSelectionRule.ForTenant ->
                    requireNoTie(byTenant.put(rule.tenantId, rule.resolverId), "tenant", rule.tenantId.value)
            }
        }
        entityTypeRules = byEntityType
        workflowRules = byWorkflow
        tenantRules = byTenant
    }

    /**
     * Returns the resolver ID chosen by the most specific matching tier, or
     * `null` when no rule matches [context].
     *
     * Pure and total: performs no registry lookup and never throws.
     */
    public fun select(context: ConflictResolverSelectionContext): ConflictResolverId? =
        entityTypeRules[context.entityType]
            ?: workflowRules[context.workflowId]
            ?: context.tenantId?.let { tenantRules[it] }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ConflictResolverSelectionPolicy && rules.toSet() == other.rules.toSet())

    override fun hashCode(): Int = rules.toSet().hashCode()

    /** Safe diagnostic string listing each rule's tier, key, and resolver ID. */
    override fun toString(): String =
        "ConflictResolverSelectionPolicy(rules=[" +
            rules.joinToString { rule ->
                when (rule) {
                    is ConflictResolverSelectionRule.ForEntityType ->
                        "entityType=${rule.entityType.value}->${rule.resolverId.value}"
                    is ConflictResolverSelectionRule.ForWorkflow ->
                        "workflow=${rule.workflowId.value}->${rule.resolverId.value}"
                    is ConflictResolverSelectionRule.ForTenant ->
                        "tenant=${rule.tenantId.value}->${rule.resolverId.value}"
                }
            } +
            "])"

    private companion object {
        fun requireNoTie(previous: ConflictResolverId?, tier: String, key: String) {
            require(previous == null) {
                "ConflictResolverSelectionPolicy: more than one $tier rule for '$key'. " +
                    "Each key may appear in at most one rule per tier."
            }
        }
    }
}
