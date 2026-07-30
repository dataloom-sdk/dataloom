package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Independent timeout limits used by retry-capable synchronization runtime paths.
 *
 * Each timeout has a distinct owner and must not be silently substituted for
 * another timeout. A null value leaves that boundary unconfigured.
 */
public data class RetryTimeoutConfiguration(
    /** Maximum time allowed to establish a remote connection. */
    public val connectionTimeout: SchedulingDelay? = null,
    /** Maximum time allowed for one request/response exchange. */
    public val requestTimeout: SchedulingDelay? = null,
    /** Maximum time allowed without observable transfer progress. */
    public val idleTimeout: SchedulingDelay? = null,
    /** Maximum time allowed for one provider invocation. */
    public val providerTimeout: SchedulingDelay? = null,
    /** Maximum time allowed for one retry-policy evaluation. */
    public val policyTimeout: SchedulingDelay? = null,
    /** Maximum time allowed for the complete synchronization workflow. */
    public val workflowTimeout: SchedulingDelay? = null,
) {
    init {
        require(hasAtLeastOneConfiguredTimeout()) {
            "RetryTimeoutConfiguration requires at least one configured timeout."
        }
    }

    /** Returns the configured timeout for [kind], or null when that boundary is disabled. */
    public fun timeoutFor(kind: RetryTimeoutKind): SchedulingDelay? = when (kind) {
        RetryTimeoutKind.CONNECTION -> connectionTimeout
        RetryTimeoutKind.REQUEST -> requestTimeout
        RetryTimeoutKind.IDLE -> idleTimeout
        RetryTimeoutKind.PROVIDER -> providerTimeout
        RetryTimeoutKind.POLICY -> policyTimeout
        RetryTimeoutKind.WORKFLOW -> workflowTimeout
    }

    private fun hasAtLeastOneConfiguredTimeout(): Boolean =
        connectionTimeout != null || requestTimeout != null || idleTimeout != null ||
            providerTimeout != null || policyTimeout != null || workflowTimeout != null
}

/** Stable timeout boundaries. Persist names rather than enum ordinals. */
public enum class RetryTimeoutKind {
    CONNECTION,
    REQUEST,
    IDLE,
    PROVIDER,
    POLICY,
    WORKFLOW,
}
