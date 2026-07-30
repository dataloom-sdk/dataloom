package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Central bounds for normalized provider/server retry delay hints.
 *
 * When this configuration is supplied, a hint is clamped to
 * [maximumHintDelay], exposed to policy in its bounded form, and enforced as a
 * minimum after policy evaluation. A policy may return a longer delay or stop;
 * it cannot make the accepted retry earlier than the bounded hint.
 *
 * Omitting this configuration preserves existing behavior: runtime retry paths
 * neither expose nor enforce hints.
 */
public data class RetryHintConfiguration(
    /** Maximum provider/server hint accepted by the shared runtime. */
    public val maximumHintDelay: SchedulingDelay,
)
