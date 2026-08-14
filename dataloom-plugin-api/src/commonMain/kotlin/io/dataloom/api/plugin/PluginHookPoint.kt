package io.dataloom.api.plugin

/**
 * Closed set of subsystem families a plugin may declare an extension point
 * for, matching #98's required "stable extension points for policy,
 * conflict, diagnostics, events, metrics, and workflow interceptors beyond
 * the provider SPI."
 *
 * [PluginHookPoint] identifies *where* a plugin can extend DataLoom, not
 * the actual callback call signature for that extension point — each
 * signature is owned by the subsystem it extends (policy: #93's policy
 * foundation; conflict: #95; diagnostics/events/metrics: #96; workflow
 * interceptors: the runtime pipeline) and is deliberately not defined by
 * this module. Adopting a hook point in a specific subsystem is separate,
 * later integration work for that subsystem, not this contract freeze.
 */
public enum class PluginHookPoint {
    /** Extends deterministic policy evaluation ([io.dataloom.api.policy.PolicyEvaluator]'s family). */
    POLICY,

    /** Extends conflict detection or resolution. */
    CONFLICT,

    /** Extends diagnostic/health reporting. */
    DIAGNOSTICS,

    /** Extends operational event emission or observation. */
    EVENTS,

    /** Extends metrics collection or export. */
    METRICS,

    /** Extends synchronization workflow execution as an interceptor. */
    WORKFLOW_INTERCEPTOR,
}
