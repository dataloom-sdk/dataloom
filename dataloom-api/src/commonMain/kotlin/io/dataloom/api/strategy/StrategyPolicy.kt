package io.dataloom.api.strategy

/** Source from which synchronized data is returned to the caller. */
public enum class StrategyDataOrigin {
    NONE,
    LOCAL,
    REMOTE,
    MIXED,
}

/** Runtime connectivity evidence supplied to deterministic strategy policy. */
public enum class StrategyConnectivity {
    AVAILABLE,
    LIMITED,
    UNAVAILABLE,
    UNKNOWN,
    NOT_EVALUATED,
}

/** Freshness classification of synchronized local state. */
public enum class StrategyCacheState {
    FRESH,
    STALE,
    MISSING,
    UNKNOWN,
    NOT_EVALUATED,
}

/** Bounded provider-health evidence; it contains no exception or sensitive data. */
public enum class StrategyProviderHealth {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN,
    NOT_EVALUATED,
}

/** Typed remote outcome categories that may participate in explicit fallback. */
public enum class StrategyRemoteOutcome {
    UNAVAILABLE,
    TIMEOUT,
    RATE_LIMITED,
    SERVER_FAILURE,
    AUTHENTICATION_FAILURE,
    AUTHORIZATION_FAILURE,
    VALIDATION_FAILURE,
    INTEGRITY_FAILURE,
    CONFLICT,
    CANCELLED,
    UNKNOWN_FAILURE,
}

/** Consistency guarantee requested by an effective strategy profile. */
public enum class StrategyConsistency {
    EVENTUAL,
    READ_YOUR_WRITES,
    REMOTE_AUTHORITATIVE,
    LOCAL_AUTHORITATIVE,
}

/** Policy to apply when connectivity evidence is unknown. */
public enum class UnknownConnectivityPolicy {
    ATTEMPT_REMOTE,
    DEFER,
    REJECT,
}

/** Policy for stale local synchronized state. */
public enum class StaleCachePolicy {
    REJECT,
    SERVE_STALE,
    SERVE_STALE_AND_REFRESH,
}

/**
 * Immutable, non-sensitive evidence used during strategy evaluation.
 *
 * Evidence is supplied by the runtime before evaluation. Evaluators are
 * side-effect-free and must not query providers while deciding.
 */
public data class StrategyRuntimeEvidence(
    public val connectivity: StrategyConnectivity = StrategyConnectivity.NOT_EVALUATED,
    public val cacheState: StrategyCacheState = StrategyCacheState.NOT_EVALUATED,
    public val storageHealth: StrategyProviderHealth = StrategyProviderHealth.NOT_EVALUATED,
    public val transportHealth: StrategyProviderHealth = StrategyProviderHealth.NOT_EVALUATED,
    public val queueHealth: StrategyProviderHealth = StrategyProviderHealth.NOT_EVALUATED,
    public val hasPendingLocalChanges: Boolean = false,
    public val isBackgroundExecutionAvailable: Boolean = false,
)
