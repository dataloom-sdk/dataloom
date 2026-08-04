package io.dataloom.consumer

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyExecutionPlanCodec
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability

public fun strategyAcceptedPlanCodecExternalConsumerProbe(): StrategyExecutionPlan {
    val plan = StrategyExecutionPlan(
        id = StrategyPlanId("external-plan"),
        requestedStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        effectiveProfileId = StrategyProfileId("external-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(
            StrategyOperation.ACCEPT_LOCAL,
            StrategyOperation.ENQUEUE_DURABLE_WORK,
        ),
        requiredCapabilities = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.QUEUE,
        ),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
            ),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )
    return StrategyExecutionPlanCodec.decode(StrategyExecutionPlanCodec.encode(plan))
}
