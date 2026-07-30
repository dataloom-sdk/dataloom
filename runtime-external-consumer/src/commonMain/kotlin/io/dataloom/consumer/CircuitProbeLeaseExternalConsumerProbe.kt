package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason

/** Compile-only use of the public half-open probe lease surface. */
internal fun compileCircuitProbeLeaseConsumer(
    state: CircuitBreakerState,
    recordResult: CircuitBreakerRecordResult,
): DataLoomInstant? {
    val configuration = CircuitBreakerConfiguration(
        failureThreshold = 3,
        failureWindow = SchedulingDelay(30_000L),
        openDuration = SchedulingDelay(60_000L),
        halfOpenProbeLeaseDuration = SchedulingDelay(15_000L),
    )
    configuration.halfOpenProbeLeaseDuration
    CircuitBreakerRejectionReason.PROBE_LEASE_DEADLINE_EXHAUSTED
    if (recordResult is CircuitBreakerRecordResult.ProbeLeaseExpired) {
        return recordResult.leaseUntil
    }
    return state.probeLeaseUntil
}
