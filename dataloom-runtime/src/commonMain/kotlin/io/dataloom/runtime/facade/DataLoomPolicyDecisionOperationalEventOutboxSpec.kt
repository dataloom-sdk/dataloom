package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on the durable operational-event
 * outbox bridge for policy decisions: every [io.dataloom.api.policy.PolicyDecision]
 * strategy-admission policy evaluation produces (see
 * [DataLoomStrategyAdmissionPolicySpec]) is translated into an
 * [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [io.dataloom.runtime.observation.operational.PolicyDecisionOperationalEventBridge]
 * and durably appended to [DurableOperationalEventOutbox] -- for operator
 * visibility and audit, never for replay-driven re-evaluation or admission.
 *
 * ## Why a separate spec
 *
 * Applying the same questions the other operational-event outbox specs
 * document: policy decisions have their own identity space
 * (`PolicySetId` + `ExecutionId`), they exist only when
 * [DataLoomBuilder.strategyAdmissionPolicyConfiguration] is configured, and
 * folding them into another domain's scope would conflate an audit stream
 * with unrelated diagnostics. Like the strategy-decision spec, this one only
 * ever bridges a result that a separately-configured capability already
 * produces, so configuring it *without*
 * [DataLoomBuilder.strategyAdmissionPolicyConfiguration] has no effect --
 * there is never a decision to bridge. It is independent of
 * [DataLoomStrategyAdmissionPolicySpec.decisionLogStore]: the durable
 * per-execution log and this outbox stream are separately opt-in.
 *
 * When [DataLoomBuilder.policyDecisionOperationalEventOutboxConfiguration] is
 * not called, behavior is unchanged from before this spec existed: no
 * envelope is ever constructed or appended for a policy decision.
 *
 * ## Ordering and failure posture
 *
 * The decision has already determined admission before it is bridged, and a
 * bridging failure (envelope construction or append) is swallowed -- it never
 * changes admission, the [io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult],
 * or the per-execution decision log commit that precedes it. Only
 * cancellation propagates.
 *
 * ## Not bridged: policy *configuration* history
 *
 * `DurableConfigurationHistory` exists in `dataloom-api` but nothing in the
 * runtime records a configuration version into it today, so there is no
 * configuration-change event to bridge; none is invented here.
 *
 * @param store a real [DurableStateStore] for [DurableOperationalEventOutbox]
 *   to persist bridged envelopes into. The application chooses the backing
 *   implementation.
 * @param scope the single [OperationalEventOutboxScope] every bridged policy
 *   decision is appended under. Defaults to
 *   `OperationalEventOutboxScope("policy-decision-events")`.
 * @param schemaVersion passed through to [DurableOperationalEventOutbox]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [DurableOperationalEventOutbox]'s own retry-bound parameter.
 */
public class DataLoomPolicyDecisionOperationalEventOutboxSpec(
    public val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    public val scope: OperationalEventOutboxScope = OperationalEventOutboxScope(DEFAULT_SCOPE_VALUE),
    public val schemaVersion: Int = DurableOperationalEventOutbox.CURRENT_SCHEMA_VERSION,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    private companion object {
        const val DEFAULT_SCOPE_VALUE: String = "policy-decision-events"
    }
}
