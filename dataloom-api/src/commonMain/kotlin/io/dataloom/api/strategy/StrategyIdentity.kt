package io.dataloom.api.strategy

import kotlin.jvm.JvmInline

private const val MAXIMUM_STRATEGY_IDENTIFIER_LENGTH: Int = 256

/**
 * Stable identifier for a synchronization strategy profile.
 *
 * The value is persisted with durable work and must therefore remain stable
 * across retries, process restart, and application upgrades.
 */
@JvmInline
public value class StrategyProfileId(public val value: String) {
    init {
        require(value.isNotBlank()) { "StrategyProfileId value must not be blank." }
        require(value.length <= MAXIMUM_STRATEGY_IDENTIFIER_LENGTH) {
            "StrategyProfileId value must not exceed " +
                "$MAXIMUM_STRATEGY_IDENTIFIER_LENGTH characters."
        }
    }

    override fun toString(): String = value
}
/** Monotonic version of an immutable strategy configuration snapshot. */
@JvmInline
public value class StrategyConfigurationVersion(public val value: Long) {
    init {
        require(value > 0L) { "StrategyConfigurationVersion value must be greater than zero." }
    }

    override fun toString(): String = value.toString()
}

/** Correlation identifier for one deterministic strategy-policy decision. */
@JvmInline
public value class StrategyDecisionId(public val value: String) {
    init {
        require(value.isNotBlank()) { "StrategyDecisionId value must not be blank." }
        require(value.length <= MAXIMUM_STRATEGY_IDENTIFIER_LENGTH) {
            "StrategyDecisionId value must not exceed " +
                "$MAXIMUM_STRATEGY_IDENTIFIER_LENGTH characters."
        }
    }

    override fun toString(): String = value
}

/** Correlation identifier for the immutable execution plan selected by policy. */
@JvmInline
public value class StrategyPlanId(public val value: String) {
    init {
        require(value.isNotBlank()) { "StrategyPlanId value must not be blank." }
        require(value.length <= MAXIMUM_STRATEGY_IDENTIFIER_LENGTH) {
            "StrategyPlanId value must not exceed " +
                "$MAXIMUM_STRATEGY_IDENTIFIER_LENGTH characters."
        }
    }

    override fun toString(): String = value
}

/** Closed set of complete synchronization strategies built into DataLoom V1. */
public enum class BuiltInSynchronizationStrategy {
    OFFLINE_FIRST,
    REMOTE_FIRST,
    CACHE_FIRST,
    NETWORK_ONLY,
    HYBRID,
    ADAPTIVE,
}
