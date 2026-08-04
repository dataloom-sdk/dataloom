package io.dataloom.api.strategy

/**
 * Returns true only when this durable decision describes the exact immutable
 * identity fields of [plan].
 *
 * This check performs no provider access, policy evaluation, clock read, I/O,
 * identifier generation, or mutation. Full plan equality remains a separate
 * queue encoder/resolver correspondence requirement.
 */
public fun PersistedStrategyDecision.correspondsTo(
    plan: StrategyExecutionPlan,
): Boolean =
    planId == plan.id &&
        requestedStrategy == plan.requestedStrategy &&
        effectiveProfileId == plan.effectiveProfileId &&
        effectiveStrategy == plan.effectiveStrategy &&
        configurationVersion == plan.configurationVersion &&
        disposition == plan.disposition
