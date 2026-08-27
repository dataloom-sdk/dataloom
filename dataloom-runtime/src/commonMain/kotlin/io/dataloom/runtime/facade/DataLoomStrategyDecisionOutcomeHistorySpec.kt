package io.dataloom.runtime.facade

import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDecisionOutcomeHistoryState

/**
 * Application-owned configuration that turns on durable per-attempt
 * strategy-decision outcome history: every terminal-outcome attempt
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]
 * records via [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] is
 * also appended to [io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory]
 * -- for operator visibility and debugging, never for replay or continuation.
 *
 * ## Why this is a separate spec rather than folding into [DataLoomStrategyDiagnosticsSpec]
 *
 * [DataLoomStrategyDiagnosticsSpec] configures
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog], a commit-once
 * single-slot log: the first-recorded terminal outcome per
 * [StrategyDecisionId], with a later differing outcome reported as a
 * conflict and never persisted (see that log's own "Why outcome mismatches
 * are not treated as a caller bug" documentation). This spec configures the
 * append-only counterpart instead --
 * [io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory] -- which
 * retains every attempt, including ones that repeat a previous attempt's
 * outcome. The two durable domains answer different questions ("what is the
 * canonical outcome" versus "what did every attempt actually produce, in
 * order") and are configured independently for that reason, the same way
 * [DataLoomStrategyDecisionOperationalEventOutboxSpec] is its own spec
 * rather than an extension of an existing one.
 *
 * ## Why this has no effect unless [DataLoomStrategyDiagnosticsSpec] is also configured
 *
 * A [io.dataloom.api.strategy.StrategyDecisionEvent] is only ever
 * constructed at all when [DataLoomBuilder.strategyDiagnosticsConfiguration]
 * is configured -- see
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]'s
 * `recordDecisionEvent`, the sole place the event is built, guarded by its
 * own `strategyDecisionEventLog` collaborator. This spec's history therefore
 * always appends the exact same already-constructed event
 * [DataLoomStrategyDiagnosticsSpec]'s log already recorded, never a second,
 * independently constructed one -- configuring this spec without also
 * configuring [DataLoomBuilder.strategyDiagnosticsConfiguration] has no
 * effect, since there is never an event to append. This mirrors exactly how
 * [DataLoomStrategyDecisionOperationalEventOutboxSpec] alone, without
 * [DataLoomBuilder.strategyDiagnosticsConfiguration], has no effect either.
 *
 * When [DataLoomBuilder.strategyDecisionOutcomeHistoryConfiguration] is not
 * called, behavior is unchanged from before this spec existed: no attempt is
 * ever appended to a
 * [io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory].
 *
 * @param store a real [DurableStateStore] for
 *   [io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory] to
 *   persist per-attempt outcome history into. The application chooses the
 *   backing implementation (Room, in-memory, or its own) -- [DataLoomBuilder]
 *   does not select one.
 * @param maxRetainedAttempts passed through to
 *   [io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory]'s own
 *   bounded-retention parameter.
 * @param schemaVersion passed through to
 *   [io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [io.dataloom.api.strategy.DurableStrategyDecisionOutcomeHistory]'s own
 *   retry-bound parameter.
 */
public class DataLoomStrategyDecisionOutcomeHistorySpec(
    public val store: DurableStateStore<StrategyDecisionId, StrategyDecisionOutcomeHistoryState>,
    public val maxRetainedAttempts: Int = 10,
    public val schemaVersion: Int = 1,
    public val maximumStateUpdateAttempts: Int = 8,
)
