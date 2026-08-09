package io.dataloom.api.policy

import io.dataloom.api.configuration.ConfigurationKey

/**
 * Well-known [ConfigurationKey] constants this foundation itself reads from a
 * [PolicyEvaluationInput.configurationSnapshot].
 *
 * ## The precedence override
 *
 * ADR-0002 states: "required user action dominates delay unless an approved
 * configuration says otherwise." This object defines the one concrete,
 * implementable mechanism for "unless":
 * [DEFER_DOMINATES_REQUIRE_USER_ACTION], read as a
 * [io.dataloom.api.configuration.ConfigurationValue.BooleanValue] from
 * [PolicyEvaluationInput.configurationSnapshot] by [PolicyEvaluator.evaluate].
 * See that method's KDoc for exactly how it is consulted.
 *
 * This is deliberately the *only* key this slice defines. Deny's dominance
 * has no override key at all — see [PolicyEvaluator.evaluate] for why.
 *
 * ## "Approved" means "reached this foundation through a `ConfigurationSnapshot`"
 *
 * A [io.dataloom.api.configuration.ConfigurationSnapshot] cannot be
 * hand-assembled with an arbitrary out-of-band value: its constructor is
 * internal, and every snapshot is produced by
 * [io.dataloom.api.configuration.ConfigurationSnapshot.create] — directly, or
 * via [io.dataloom.api.configuration.DataLoomConfigurationResolver], which
 * only admits a snapshot after schema and precedence validation succeeds.
 * "Approved configuration" in this design means exactly that: the override
 * flag can only reach [PolicyEvaluator] by being present in a snapshot that
 * already went through that admission path. If the host application's
 * [io.dataloom.api.configuration.ConfigurationSchema] does not declare this
 * key, [io.dataloom.api.configuration.DataLoomConfigurationResolver] rejects
 * any source attempting to supply it as an unknown key — the override is
 * opt-in at the schema level, not silently available by default.
 */
public object PolicyConfigurationKeys {

    /**
     * When present in a [PolicyEvaluationInput.configurationSnapshot] as
     * [io.dataloom.api.configuration.ConfigurationValue.BooleanValue] `true`,
     * lets a [PolicyCheckOutcome.Defer] outcome win over a
     * [PolicyCheckOutcome.RequireUserAction] outcome within the same
     * [PolicySet] evaluation. Absent, `false`, or present with a different
     * [io.dataloom.api.configuration.ConfigurationValue] type is treated as
     * `false` — the default precedence (required user action dominates
     * delay) always applies unless this key is unambiguously `true`.
     *
     * Never overrides [PolicyCheckOutcome.Deny]'s dominance over anything.
     */
    public val DEFER_DOMINATES_REQUIRE_USER_ACTION: ConfigurationKey =
        ConfigurationKey("dataloom.policy.deferDominatesRequireUserAction")
}
