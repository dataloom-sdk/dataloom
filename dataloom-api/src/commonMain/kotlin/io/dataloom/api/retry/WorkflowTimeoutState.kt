package io.dataloom.api.retry

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant

/**
 * Immutable accepted workflow-timeout evidence.
 *
 * [startedAt] and [deadline] are absolute wall-clock instants selected when a
 * workflow is admitted. Durable queue, retry, deferral, lease recovery, and
 * restart paths must preserve this exact value rather than deriving a new
 * deadline from later runtime configuration.
 *
 * A zero-duration workflow is represented by `deadline == startedAt` and is
 * expired at that exact instant. Deadline derivation saturates at
 * [Long.MAX_VALUE] instead of overflowing into an earlier instant.
 */
public data class WorkflowTimeoutState(
    /** Absolute instant at which the workflow timeout window was accepted. */
    public val startedAt: DataLoomInstant,

    /** Absolute exclusive deadline for the complete workflow. */
    public val deadline: DataLoomInstant,
) {
    init {
        require(deadline.epochMilliseconds >= startedAt.epochMilliseconds) {
            "WorkflowTimeoutState deadline must not be earlier than startedAt."
        }
    }

    public companion object {
        /**
         * Creates immutable timeout evidence from [startedAt] and [timeout].
         *
         * The deadline calculation is overflow-safe and saturates at
         * [Long.MAX_VALUE].
         */
        public fun from(
            startedAt: DataLoomInstant,
            timeout: SchedulingDelay,
        ): WorkflowTimeoutState = WorkflowTimeoutState(
            startedAt = startedAt,
            deadline = DataLoomInstant(
                addSaturated(startedAt.epochMilliseconds, timeout.milliseconds),
            ),
        )

        private fun addSaturated(left: Long, right: Long): Long {
            if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
            return left + right
        }
    }
}
