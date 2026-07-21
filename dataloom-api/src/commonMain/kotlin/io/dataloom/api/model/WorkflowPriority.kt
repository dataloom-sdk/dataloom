package io.dataloom.api.model

/**
 * Canonical workflow priority.
 *
 * Scheduler interpretation of these priorities is defined in later issues.
 */
public enum class WorkflowPriority {
    /** Lower urgency than the default priority level. */
    LOW,

    /** Default conceptual workflow priority. */
    NORMAL,

    /** Higher urgency than the default priority level. */
    HIGH,

    /** Highest urgency level without bypassing policy or safety controls. */
    CRITICAL,
}
