package io.dataloom.api.configuration

/** Severity of one [ConfigurationValidationFinding]. */
public enum class ConfigurationFindingSeverity {
    /** Blocks admission — see [ConfigurationValidationResult.isValid]. */
    ERROR,

    /** Reported but does not block admission. */
    WARNING,
}

/**
 * One structured validation finding produced while resolving a
 * [ConfigurationSnapshot].
 *
 * @param severity whether this finding blocks admission.
 * @param key the offending [ConfigurationKey], or `null` for a finding not
 *   tied to a single key.
 * @param message a human-readable description. Must never include a secret
 *   value — see [ConfigurationValue.SecretReferenceValue].
 */
public data class ConfigurationValidationFinding(
    public val severity: ConfigurationFindingSeverity,
    public val key: ConfigurationKey?,
    public val message: String,
)

/**
 * Complete set of validation findings produced by one resolution attempt.
 *
 * A candidate configuration is admitted only when [isValid] is `true` — that
 * is, when [findings] contains no [ConfigurationFindingSeverity.ERROR]
 * finding. [findings] may still be non-empty when [isValid] is `true`: a
 * resolution can produce warnings and still be admitted.
 */
public class ConfigurationValidationResult(
    public val findings: List<ConfigurationValidationFinding>,
) {
    /** `true` when [findings] contains no [ConfigurationFindingSeverity.ERROR] finding. */
    public val isValid: Boolean = findings.none { it.severity == ConfigurationFindingSeverity.ERROR }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ConfigurationValidationResult && findings == other.findings)

    override fun hashCode(): Int = findings.hashCode()

    override fun toString(): String =
        "ConfigurationValidationResult(isValid=$isValid, findingCount=${findings.size})"
}
