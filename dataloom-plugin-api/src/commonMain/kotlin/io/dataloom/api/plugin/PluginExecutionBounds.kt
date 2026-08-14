package io.dataloom.api.plugin

/**
 * Declared platform-appropriate time and concurrency bounds for one
 * plugin's invocations, matching #98's required "platform-appropriate
 * time/resource/concurrency/cancellation/failure bounds."
 *
 * This is a declared-bounds shape only — enforcing these bounds at
 * invocation time (timeout cancellation, concurrency limiting, failure
 * isolation/bulkheading so one plugin cannot corrupt or stop unrelated
 * workflows) is runtime behavior owned by the plugin lifecycle engine
 * (#98), not this contract module.
 *
 * @param maximumExecutionMillis the longest one invocation may run before
 *   the engine is expected to cancel it. Must be positive.
 * @param maximumConcurrentInvocations the most invocations of this plugin
 *   the engine is expected to permit at once. Must be positive.
 */
public data class PluginExecutionBounds(
    public val maximumExecutionMillis: Long,
    public val maximumConcurrentInvocations: Int,
) {
    init {
        require(maximumExecutionMillis > 0) {
            "maximumExecutionMillis must be positive, but was $maximumExecutionMillis."
        }
        require(maximumConcurrentInvocations > 0) {
            "maximumConcurrentInvocations must be positive, but was $maximumConcurrentInvocations."
        }
    }
}
