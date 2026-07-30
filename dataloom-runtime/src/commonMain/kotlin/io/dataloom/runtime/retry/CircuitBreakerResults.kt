package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.time.DataLoomInstant

/** Stable reason for rejecting execution before an operation starts. */
public enum class CircuitBreakerRejectionReason {
    OPEN,
    PROBE_IN_FLIGHT,
    CLOCK_REGRESSION,
    PROBE_GENERATION_EXHAUSTED,
}

/** Permit for the single controlled half-open probe of one generation. */
public data class CircuitBreakerProbePermit(
    public val scope: CircuitBreakerScope,
    public val generation: Long,
) {
    init {
        require(generation > 0L) {
            "CircuitBreakerProbePermit generation must be greater than zero."
        }
    }
}

/** Result of checking a circuit before an operation starts. */
public sealed interface CircuitBreakerPermission {
    public data object Allowed : CircuitBreakerPermission

    public data class ProbeAllowed(
        public val permit: CircuitBreakerProbePermit,
        public val record: CircuitBreakerStateRecord,
    ) : CircuitBreakerPermission

    public data class Rejected(
        public val reason: CircuitBreakerRejectionReason,
        public val retryAt: DataLoomInstant? = null,
    ) : CircuitBreakerPermission

    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : CircuitBreakerPermission

    public data object ContentionLimitReached : CircuitBreakerPermission
}

/** Result of recording a successful or failed operation outcome. */
public sealed interface CircuitBreakerRecordResult {
    public data class Recorded(
        public val record: CircuitBreakerStateRecord,
    ) : CircuitBreakerRecordResult

    public data object Ignored : CircuitBreakerRecordResult

    public data object StaleProbe : CircuitBreakerRecordResult

    public data class ClockRegression(
        public val observedAt: DataLoomInstant,
        public val persistedAt: DataLoomInstant,
    ) : CircuitBreakerRecordResult

    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : CircuitBreakerRecordResult

    public data object ContentionLimitReached : CircuitBreakerRecordResult
}
