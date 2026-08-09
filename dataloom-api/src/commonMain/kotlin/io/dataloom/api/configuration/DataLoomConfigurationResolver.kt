package io.dataloom.api.configuration

import io.dataloom.api.security.DataLoomDigestCalculator

/** Outcome of one [DataLoomConfigurationResolver.resolve] call. */
public sealed class ConfigurationResolution {

    /** All findings produced during resolution, including on [Admitted]. */
    public abstract val findings: List<ConfigurationValidationFinding>

    /**
     * Resolution succeeded: [snapshot] is ready to apply, and [findings]
     * contains no error-severity entries (it may still contain warnings).
     */
    public data class Admitted(
        public val snapshot: ConfigurationSnapshot,
        override val findings: List<ConfigurationValidationFinding>,
    ) : ConfigurationResolution()

    /**
     * Resolution failed: [findings] contains at least one error-severity
     * entry, and no snapshot was produced.
     */
    public data class Rejected(
        override val findings: List<ConfigurationValidationFinding>,
    ) : ConfigurationResolution()
}

/**
 * Resolves one or more [ConfigurationSource] layers into a single validated,
 * checksummed [ConfigurationSnapshot], according to a fixed
 * [ConfigurationSchema] and [ConfigurationScope] precedence.
 *
 * ## What this does not do
 *
 * [DataLoomConfigurationResolver] performs no I/O, network access, or
 * persistence, and does not itself enforce monotonically increasing
 * [ConfigurationSnapshot.version] numbers across calls — see
 * [DataLoomConfigurationHistory] for applying resolved snapshots over time
 * with version and rollback tracking.
 *
 * ## Injection
 *
 * Must be injected; must not be accessed through a global singleton.
 *
 * @param schema the fixed set of keys and types this resolver admits.
 * @param digestCalculator used to compute each admitted snapshot's integrity
 *   checksum.
 */
public class DataLoomConfigurationResolver(
    private val schema: ConfigurationSchema,
    private val digestCalculator: DataLoomDigestCalculator,
) {

    /**
     * Merges [sources] in ascending [ConfigurationScope] precedence (a
     * higher-precedence source's value for a key wins over a
     * lower-precedence source's value for the same key) and validates the
     * result against [schema] before admission.
     *
     * Validation is exhaustive, not fail-fast: every unknown key,
     * type mismatch, and missing required key is reported as its own
     * finding in one [ConfigurationResolution.Rejected] result, rather than
     * stopping at the first problem found. A key that some source attempted
     * to supply but was rejected (unknown key or type mismatch) is reported
     * once, for that reason — it is not additionally reported as "missing"
     * even though it did not end up in the merged result.
     *
     * @param sources the source layers to merge. Sources may be supplied in
     *   any order; [ConfigurationSource.scope] determines precedence, not
     *   list position.
     * @param version the version to stamp an admitted snapshot with. The
     *   caller is responsible for monotonicity across calls — see
     *   [DataLoomConfigurationHistory.apply].
     */
    public fun resolve(sources: List<ConfigurationSource>, version: Long): ConfigurationResolution {
        val findings = mutableListOf<ConfigurationValidationFinding>()
        val merged = linkedMapOf<ConfigurationKey, ConfigurationValue>()
        val mergedScope = mutableMapOf<ConfigurationKey, ConfigurationScope>()
        // Keys at least one source attempted to supply, valid or not — used
        // below to avoid double-reporting a key both as "wrong type" (or
        // "unknown") and, redundantly, as "missing" for the same root cause.
        val attemptedKeys = mutableSetOf<ConfigurationKey>()

        for (source in sources.sortedBy { it.scope.ordinal }) {
            for ((key, value) in source.entries) {
                attemptedKeys += key
                val declared = schema[key]
                when {
                    declared == null -> findings += ConfigurationValidationFinding(
                        severity = ConfigurationFindingSeverity.ERROR,
                        key = key,
                        message = "Key '$key' is not declared in the configuration schema.",
                    )

                    declared.type != value.type -> findings += ConfigurationValidationFinding(
                        severity = ConfigurationFindingSeverity.ERROR,
                        key = key,
                        message = "Key '$key' expects ${declared.type} but source scope " +
                            "${source.scope} supplied ${value.type}.",
                    )

                    else -> {
                        // Two sources at the *same* scope disagreeing on a key is not
                        // itself wrong (precedence between sibling sources at one scope
                        // is caller-list-order, applied in a stable sort), but it is
                        // worth surfacing rather than silently resolving.
                        if (mergedScope[key] == source.scope) {
                            findings += ConfigurationValidationFinding(
                                severity = ConfigurationFindingSeverity.WARNING,
                                key = key,
                                message = "Key '$key' was supplied by more than one source at " +
                                    "scope ${source.scope}; the later source in the supplied " +
                                    "list order won.",
                            )
                        }
                        merged[key] = value
                        mergedScope[key] = source.scope
                    }
                }
            }
        }

        for (declared in schema.entries.values) {
            if (declared.required && declared.key !in merged && declared.key !in attemptedKeys) {
                findings += ConfigurationValidationFinding(
                    severity = ConfigurationFindingSeverity.ERROR,
                    key = declared.key,
                    message = "Required key '${declared.key}' was not supplied by any source.",
                )
            }
        }

        if (findings.any { it.severity == ConfigurationFindingSeverity.ERROR }) {
            return ConfigurationResolution.Rejected(findings)
        }

        val snapshot = ConfigurationSnapshot.create(version, merged, digestCalculator)
        return ConfigurationResolution.Admitted(snapshot, findings)
    }
}
