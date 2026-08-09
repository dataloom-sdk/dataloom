package io.dataloom.api.configuration

/** Outcome of one [DataLoomConfigurationHistory.apply] call. */
public sealed class ConfigurationApplyOutcome {

    /** [snapshot] became the new [DataLoomConfigurationHistory.current] snapshot. */
    public data class Applied(public val snapshot: ConfigurationSnapshot) : ConfigurationApplyOutcome()

    /**
     * [snapshot] was rejected because its [ConfigurationSnapshot.version] did
     * not strictly increase past [currentVersion]. [DataLoomConfigurationHistory.current]
     * did not change.
     */
    public data class VersionNotMonotonic(
        public val snapshot: ConfigurationSnapshot,
        public val currentVersion: Long,
    ) : ConfigurationApplyOutcome()
}

/**
 * Bounded, in-memory, monotonically versioned history of applied
 * [ConfigurationSnapshot] instances, supporting rollback to the last known
 * good snapshot.
 *
 * ## Scope
 *
 * This is an in-memory primitive only. It does not persist history across a
 * process restart and provides no distributed compare-and-set/fencing
 * guarantee — durable, transactional configuration state is separate,
 * out-of-scope follow-up work. See docs/api/configuration-snapshots.md.
 *
 * ## Monotonic versioning
 *
 * [apply] only accepts a candidate snapshot whose [ConfigurationSnapshot.version]
 * is strictly greater than [current]'s version (or any version, if [current]
 * is `null`). A candidate with an equal or lower version is rejected — see
 * [ConfigurationApplyOutcome.VersionNotMonotonic] — and [current] does not
 * change.
 *
 * ## Rollback
 *
 * [rollbackToLastKnownGood] restores the snapshot applied immediately before
 * [current] and removes [current] from history. After a rollback, a future
 * [apply] call is judged solely against the restored snapshot's version: the
 * version number the rolled-back snapshot used is not specially blocked — it
 * succeeds again as long as it is still strictly greater than the newly
 * current (restored) version.
 *
 * ## Thread safety
 *
 * Instances are not safe for concurrent use from multiple threads. Callers
 * that apply or roll back from multiple threads must provide their own
 * synchronization.
 *
 * @param maxRetainedVersions the maximum number of applied snapshots kept in
 *   history, including [current]. Must be at least `1`. The oldest snapshot
 *   is discarded once this bound is exceeded.
 */
public class DataLoomConfigurationHistory(
    private val maxRetainedVersions: Int = DEFAULT_MAX_RETAINED_VERSIONS,
) {
    init {
        require(maxRetainedVersions >= 1) {
            "maxRetainedVersions must be at least 1, but was $maxRetainedVersions."
        }
    }

    private val history: ArrayDeque<ConfigurationSnapshot> = ArrayDeque()

    /** The currently applied snapshot, or `null` if [apply] has never succeeded. */
    public val current: ConfigurationSnapshot?
        get() = history.lastOrNull()

    /** Every retained version, oldest first, bounded by [maxRetainedVersions]. */
    public val retainedVersions: List<Long>
        get() = history.map { it.version }

    /**
     * Applies [snapshot] if its version strictly exceeds [current]'s
     * version, appending it to history and discarding the oldest retained
     * snapshot once [maxRetainedVersions] is exceeded.
     */
    public fun apply(snapshot: ConfigurationSnapshot): ConfigurationApplyOutcome {
        val currentVersion = current?.version
        if (currentVersion != null && snapshot.version <= currentVersion) {
            return ConfigurationApplyOutcome.VersionNotMonotonic(snapshot, currentVersion)
        }
        history.addLast(snapshot)
        while (history.size > maxRetainedVersions) {
            history.removeFirst()
        }
        return ConfigurationApplyOutcome.Applied(snapshot)
    }

    /**
     * Restores the snapshot applied immediately before [current] and drops
     * [current] from history.
     *
     * @return the restored snapshot, or `null` if there is no earlier
     *   retained snapshot to roll back to (history has zero or one entries
     *   — for example, because [maxRetainedVersions] already discarded it,
     *   or because [apply] has succeeded at most once).
     */
    public fun rollbackToLastKnownGood(): ConfigurationSnapshot? {
        if (history.size < 2) return null
        history.removeLast()
        return history.last()
    }

    private companion object {
        const val DEFAULT_MAX_RETAINED_VERSIONS: Int = 10
    }
}
