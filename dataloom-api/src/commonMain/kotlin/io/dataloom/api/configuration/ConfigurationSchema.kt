package io.dataloom.api.configuration

/**
 * Declares one allowed entry within a [ConfigurationSchema].
 *
 * @param key the entry's canonical [ConfigurationKey].
 * @param type the [ConfigurationValueType] every admitted value for [key]
 *   must match.
 * @param required when `true`, [DataLoomConfigurationResolver] reports an
 *   error-severity finding if no source layer supplies [key]. When `false`,
 *   a missing value is not itself an error, and the resolved snapshot simply
 *   omits [key].
 */
public data class ConfigurationEntrySchema(
    public val key: ConfigurationKey,
    public val type: ConfigurationValueType,
    public val required: Boolean = true,
)

/**
 * Immutable, closed declaration of every [ConfigurationKey] a
 * [DataLoomConfigurationResolver] will admit, and the [ConfigurationValueType]
 * each key must match.
 *
 * ## Unknown-key strictness
 *
 * A [ConfigurationSource] entry whose key is not declared here is an
 * error-severity validation finding, not a silently ignored value — see
 * [DataLoomConfigurationResolver.resolve]. There is no permissive or
 * passthrough mode; every admitted key must be declared.
 *
 * @param entries every declared entry. Must be non-empty and have unique
 *   [ConfigurationEntrySchema.key] values.
 * @throws IllegalArgumentException if [entries] is empty or contains a
 *   duplicate key.
 */
public class ConfigurationSchema(
    entries: Collection<ConfigurationEntrySchema>,
) {
    /** Every declared entry, keyed by [ConfigurationEntrySchema.key]. */
    public val entries: Map<ConfigurationKey, ConfigurationEntrySchema> = entries.associateBy { it.key }

    init {
        require(entries.isNotEmpty()) { "ConfigurationSchema must declare at least one entry." }
        require(this.entries.size == entries.size) {
            "ConfigurationSchema entries must have unique keys."
        }
    }

    /** Returns the declared [ConfigurationEntrySchema] for [key], or `null` if undeclared. */
    public operator fun get(key: ConfigurationKey): ConfigurationEntrySchema? = entries[key]

    override fun equals(other: Any?): Boolean =
        this === other || (other is ConfigurationSchema && entries == other.entries)

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "ConfigurationSchema(keys=${entries.keys})"
}
