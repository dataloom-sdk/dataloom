package io.dataloom.consumer

import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.runtime.queue.QueuedSynchronizationWork

/** External-consumer probe for durable strategy-decision queue evidence. */
public object DurableStrategyDecisionExternalConsumerProbe {

    public fun fromEntry(entry: QueueEntry): PersistedStrategyDecision? =
        entry.strategyDecision

    public fun fromWork(work: QueuedSynchronizationWork): PersistedStrategyDecision? =
        work.strategyDecision
}
