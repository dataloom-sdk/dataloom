package io.dataloom.runtime.facade

import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.strategy.StrategyDecisionEvent
import io.dataloom.api.strategy.StrategyDecisionId

/**
 * Application-owned configuration that turns on durable strategy-decision
 * diagnostics -- a payload-free audit trail of past strategy
 * admission/execution decisions, for operator visibility and debugging.
 *
 * ## Why this exists
 *
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] and
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]'s
 * optional `strategyDecisionEventLog` parameter exist independently of this
 * spec, but nothing in [DataLoomBuilder] constructed one before this method
 * existed. This spec is that missing connection, mirroring
 * [DataLoomConflictDetectionSpec]'s own shape and purpose for a different
 * durable-state domain.
 *
 * ## What this is not
 *
 * This is deliberately unrelated to the strategy engine's existing durable
 * *continuation* state (accepted-plan persistence for durable-queue replay,
 * configured via [DataLoomBuilder.queueSubmissionEncoder]/
 * [DataLoomBuilder.queueSubmissionConfiguration]). Enabling this spec adds a
 * diagnostic side record of what happened; it never changes what the strategy
 * engine does or replays.
 *
 * When this method is not called, behavior is unchanged from before it
 * existed: no strategy decision event is ever recorded.
 *
 * @param store a real [DurableStateStore] for
 *   [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] to persist
 *   strategy decision events into. The application chooses the backing
 *   implementation (Room, in-memory, or its own) -- [DataLoomBuilder] does
 *   not select one.
 * @param schemaVersion passed through to
 *   [io.dataloom.api.strategy.DurableStrategyDecisionEventLog]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [io.dataloom.api.strategy.DurableStrategyDecisionEventLog]'s own
 *   retry-bound parameter.
 */
public class DataLoomStrategyDiagnosticsSpec(
    public val store: DurableStateStore<StrategyDecisionId, StrategyDecisionEvent>,
    public val schemaVersion: Int = 1,
    public val maximumStateUpdateAttempts: Int = 8,
)
