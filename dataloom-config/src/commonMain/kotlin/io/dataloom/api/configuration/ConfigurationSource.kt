package io.dataloom.api.configuration

/**
 * Closed, fixed precedence tiers a [ConfigurationSource] can occupy.
 *
 * Precedence is fixed by declaration order (later wins): a [LOCAL_OVERRIDE]
 * entry always wins over a [REMOTE_ASSIGNED] entry for the same key, which
 * always wins over a [BUILT_IN_DEFAULT] entry — see
 * [DataLoomConfigurationResolver.resolve]. This three-tier model is
 * deliberately small and closed rather than an open, caller-defined
 * ordering; see docs/api/configuration-snapshots.md for the rationale.
 */
public enum class ConfigurationScope {
    /** DataLoom- or application-declared baseline values. */
    BUILT_IN_DEFAULT,

    /** Values assigned by a remote configuration or feature-flag source. */
    REMOTE_ASSIGNED,

    /** Values explicitly overridden on the local device or process. */
    LOCAL_OVERRIDE,
}

/**
 * One immutable, single-scope layer of raw configuration entries.
 *
 * [DataLoomConfigurationResolver] merges one or more [ConfigurationSource]
 * layers into a single [ConfigurationSnapshot] using [ConfigurationScope]'s
 * fixed precedence. It never reads from or writes to any external source
 * itself — producing these entries (parsing a remote payload, reading a
 * local override file, and so on) is entirely the host application's
 * responsibility.
 *
 * @param scope the fixed precedence tier this source occupies.
 * @param entries the raw entries this source layer supplies.
 */
public class ConfigurationSource(
    public val scope: ConfigurationScope,
    entries: Map<ConfigurationKey, ConfigurationValue>,
) {
    /** Defensive, read-only copy of the entries supplied at construction. */
    public val entries: Map<ConfigurationKey, ConfigurationValue> = entries.toMap()

    override fun equals(other: Any?): Boolean =
        this === other || (other is ConfigurationSource && scope == other.scope && entries == other.entries)

    override fun hashCode(): Int {
        var result = scope.hashCode()
        result = 31 * result + entries.hashCode()
        return result
    }

    override fun toString(): String = "ConfigurationSource(scope=$scope, keys=${entries.keys})"
}
