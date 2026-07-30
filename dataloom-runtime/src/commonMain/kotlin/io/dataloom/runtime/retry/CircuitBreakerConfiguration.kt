package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.SchedulingDelay

/** Deterministic circuit-breaker thresholds and transition windows. */
public data class CircuitBreakerConfiguration(
    public val failureThreshold: Int,
    public val failureWindow: SchedulingDelay,
    public val openDuration: SchedulingDelay,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    init {
        require(failureThreshold >= 1) {
            "CircuitBreakerConfiguration failureThreshold must be at least one."
        }
        require(failureWindow.milliseconds > 0L) {
            "CircuitBreakerConfiguration failureWindow must be greater than zero."
        }
        require(openDuration.milliseconds > 0L) {
            "CircuitBreakerConfiguration openDuration must be greater than zero."
        }
        require(maximumStateUpdateAttempts >= 1) {
            "CircuitBreakerConfiguration maximumStateUpdateAttempts must be at least one."
        }
    }
}
