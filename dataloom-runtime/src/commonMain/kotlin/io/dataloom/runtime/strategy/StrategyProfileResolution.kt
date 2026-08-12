package io.dataloom.runtime.strategy

import io.dataloom.api.strategy.AdaptiveStrategyProfile
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.SynchronizationStrategyProfile

/**
 * Resolves the concrete [SynchronizationStrategyProfile] an executor should
 * read its own profile-specific fields from.
 *
 * [StrategySynchronizationRequest.profile] is the profile the caller
 * originally submitted, and it is never replaced anywhere after evaluation.
 * For a plain concrete profile (e.g. `RemoteFirstStrategyProfile`) that is
 * already the correct object to cast. But when the caller submitted an
 * [AdaptiveStrategyProfile], `BuiltInSynchronizationStrategyEvaluator.evaluateAdaptive`
 * resolves it to one of its concrete candidates and records that candidate's
 * own id as [io.dataloom.api.strategy.StrategyExecutionPlan.effectiveProfileId] —
 * but `request.profile` itself stays the outer [AdaptiveStrategyProfile]. An
 * executor that blindly casts `request.profile` to its own concrete profile
 * type (as [RemoteFirstStrategyExecutor] and [HybridStrategyExecutor] both
 * need to, to read fields like `persistRemoteResult`) would throw
 * `ClassCastException` for any adaptive-resolved request reaching it — this
 * resolves the actual selected candidate by `effectiveProfileId` in that
 * case instead.
 */
internal fun resolvedProfile(
    request: StrategySynchronizationRequest,
    evaluation: StrategyEvaluationResult,
): SynchronizationStrategyProfile {
    val profile = request.profile
    return if (profile is AdaptiveStrategyProfile) {
        profile.candidates.first { it.id == evaluation.plan.effectiveProfileId }
    } else {
        profile
    }
}
