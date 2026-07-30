package io.dataloom.api.circuit

import io.dataloom.api.time.DataLoomInstant

/** Durable circuit-breaker phases. Persist names rather than enum ordinals. */
public enum class CircuitBreakerPhase {
    CLOSED,
    OPEN,
    HALF_OPEN,
}

/**
 * Immutable durable state for one [scope].
 *
 * The state contains only bounded operational evidence. It must not contain
 * payloads, credentials, headers, exception messages, or arbitrary metadata.
 */
public data class CircuitBreakerState(
    public val scope: CircuitBreakerScope,
    public val phase: CircuitBreakerPhase,
    public val consecutiveFailures: Int,
    public val failureWindowStartedAt: DataLoomInstant?,
    public val openUntil: DataLoomInstant?,
    public val probeGeneration: Long,
    public val probeInFlight: Boolean,
    public val updatedAt: DataLoomInstant,
) {
    init {
        require(consecutiveFailures >= 0) {
            "CircuitBreakerState consecutiveFailures must be zero or greater."
        }
        require(probeGeneration >= 0L) {
            "CircuitBreakerState probeGeneration must be zero or greater."
        }
        when (phase) {
            CircuitBreakerPhase.CLOSED -> validateClosed()
            CircuitBreakerPhase.OPEN -> validateOpen()
            CircuitBreakerPhase.HALF_OPEN -> validateHalfOpen()
        }
    }

    private fun validateClosed() {
        require(openUntil == null) { "A closed circuit cannot have openUntil." }
        require(!probeInFlight) { "A closed circuit cannot have an active probe." }
        if (consecutiveFailures == 0) {
            require(failureWindowStartedAt == null) {
                "A closed circuit without failures cannot have a failure-window start."
            }
        } else {
            val startedAt = requireNotNull(failureWindowStartedAt) {
                "A closed circuit with failures requires failureWindowStartedAt."
            }
            require(startedAt.epochMilliseconds <= updatedAt.epochMilliseconds) {
                "failureWindowStartedAt cannot be later than updatedAt."
            }
        }
    }

    private fun validateOpen() {
        require(consecutiveFailures == 0) { "An open circuit cannot retain closed-window failures." }
        require(failureWindowStartedAt == null) { "An open circuit cannot retain a failure window." }
        require(!probeInFlight) { "An open circuit cannot have an active probe." }
        val deadline = requireNotNull(openUntil) { "An open circuit requires openUntil." }
        require(deadline.epochMilliseconds >= updatedAt.epochMilliseconds) {
            "openUntil cannot be earlier than updatedAt."
        }
    }

    private fun validateHalfOpen() {
        require(consecutiveFailures == 0) {
            "A half-open circuit cannot retain closed-window failures."
        }
        require(failureWindowStartedAt == null) {
            "A half-open circuit cannot retain a failure window."
        }
        require(openUntil == null) { "A half-open circuit cannot have openUntil." }
        require(probeInFlight) { "A half-open circuit requires exactly one active probe." }
        require(probeGeneration > 0L) {
            "A half-open circuit requires a positive probe generation."
        }
    }
}
