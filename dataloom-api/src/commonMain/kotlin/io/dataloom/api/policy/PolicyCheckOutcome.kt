package io.dataloom.api.policy

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * The outcome of one [PolicyCheck] evaluating one [PolicyEvaluationInput], and
 * also the vocabulary [PolicyDecision.outcome] uses for the combined result.
 *
 * [PolicyEvaluator] reuses this exact sealed hierarchy for the combined
 * decision too, rather than defining a second, parallel hierarchy for "the
 * final decision." A decision, in this design, is literally the winning
 * check's own outcome plus the evidence trail that produced it — see
 * [PolicyDecision]. Ranked from most to least restrictive — see
 * [PolicyEvaluator.evaluate]'s "Precedence" section for the exact combination
 * rule:
 *
 * 1. [Deny] — unconditionally dominates every other outcome.
 * 2. [RequireUserAction] and [Defer] — dominate [Allow]; their relative order
 *    against each other is fixed by default but may be reversed for one
 *    evaluation by an approved configuration value — see
 *    [PolicyEvaluator.evaluate].
 * 3. [Allow] — the least restrictive outcome; wins only when no check
 *    produced any of the above.
 *
 * ## Every variant is explainable
 *
 * Every variant carries a required, non-blank [justification] — plain,
 * human-readable text, not a closed reason enum. A closed enum of "why" would
 * have to anticipate retry, conflict, content-policy, plugin-permission,
 * residency, and administrative-override reasons all at once; that concrete
 * vocabulary is exactly the out-of-scope subsystem-specific policy logic this
 * foundation does not design. Every variant also carries optional
 * [DataLoomMetadata], matching [io.dataloom.api.retry.RetryDecision] and
 * `ConflictResolutionDecision`'s existing pattern in this codebase.
 *
 * ## Construction restrictions
 *
 * Creating any variant does not sleep, perform I/O, mutate runtime state, or
 * apply the outcome. [PolicyCheck.evaluate] must produce these values purely
 * from its [PolicyEvaluationInput] argument.
 *
 * ## Sensitive-data restrictions
 *
 * [justification] and [metadata] on every variant must not contain
 * credentials, authentication tokens, encryption keys, personal data, or
 * payload bytes.
 */
public sealed interface PolicyCheckOutcome {

    /** Required, non-blank, human-readable rationale for this outcome. */
    public val justification: String

    /** Optional contextual metadata. Defaults to [DataLoomMetadata.Empty]. */
    public val metadata: DataLoomMetadata

    /**
     * The check found no reason to object; evaluation may proceed.
     *
     * [Allow] is the weakest outcome: any [Deny], [RequireUserAction], or
     * [Defer] elsewhere in the same [PolicySet] outranks it.
     */
    public data class Allow(
        override val justification: String,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : PolicyCheckOutcome {
        init {
            require(justification.isNotBlank()) { "PolicyCheckOutcome.Allow justification must not be blank." }
        }
    }

    /**
     * The check found a reason the evaluated action must not proceed.
     *
     * [Deny] unconditionally dominates every other [PolicyCheckOutcome] in the
     * same [PolicySet] — see [PolicyEvaluator.evaluate]. A deny is an
     * ordinary, expected, rule-based negative result (for example "plugin
     * lacks the requested permission"), not necessarily an error condition,
     * so unlike some other decision types in this codebase, [Deny] does not
     * require an [io.dataloom.api.error.DataLoomError].
     */
    public data class Deny(
        override val justification: String,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : PolicyCheckOutcome {
        init {
            require(justification.isNotBlank()) { "PolicyCheckOutcome.Deny justification must not be blank." }
        }
    }

    /**
     * The action cannot proceed automatically; it requires an explicit,
     * out-of-band user decision before it can be re-evaluated.
     *
     * [RequireUserAction] dominates [Defer] within the same [PolicySet]
     * unless an approved configuration says otherwise — see
     * [PolicyConfigurationKeys] and [PolicyEvaluator.evaluate]. This variant
     * deliberately carries no structured "what action" payload beyond
     * [justification]: the concrete UX (re-authenticate, grant a permission,
     * accept a residency term, and so on) is subsystem-specific and out of
     * scope for this generic foundation.
     */
    public data class RequireUserAction(
        override val justification: String,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : PolicyCheckOutcome {
        init {
            require(justification.isNotBlank()) {
                "PolicyCheckOutcome.RequireUserAction justification must not be blank."
            }
        }
    }

    /**
     * The action should not proceed now, but no user action is required — the
     * caller should retry evaluation later, after [delay].
     *
     * This is ADR-0002's "delay" concept. It reuses
     * [io.dataloom.api.scheduling.SchedulingDelay] — the same relative-delay
     * type retry decisions already use — rather than inventing a second
     * duration representation. A zero-millisecond delay means "postpone, but
     * retrying evaluation immediately is acceptable"; scheduling any
     * resulting work is the caller's responsibility, not this foundation's,
     * matching [SchedulingDelay]'s own documented restrictions.
     */
    public data class Defer(
        public val delay: SchedulingDelay,
        override val justification: String,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : PolicyCheckOutcome {
        init {
            require(justification.isNotBlank()) { "PolicyCheckOutcome.Defer justification must not be blank." }
        }
    }
}
